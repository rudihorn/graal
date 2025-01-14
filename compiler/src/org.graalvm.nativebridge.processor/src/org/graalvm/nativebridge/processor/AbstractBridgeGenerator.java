/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.nativebridge.processor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.UnionType;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.graalvm.nativebridge.processor.AbstractBridgeParser.AbstractTypeCache;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.DefinitionData;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.MarshallerData;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.MethodData;

abstract class AbstractBridgeGenerator {

    static final String MARSHALLED_DATA_PARAMETER = "marshalledData";
    private static final int BYTES_PER_PARAMETER = 256;

    final AbstractBridgeParser parser;
    final Types types;
    private final AbstractTypeCache typeCache;

    AbstractBridgeGenerator(AbstractBridgeParser parser) {
        this.parser = parser;
        this.types = parser.types;
        this.typeCache = parser.typeCache;
    }

    abstract void generate(DefinitionData data) throws IOException;

    abstract MarshallerSnippets marshallerSnippets(DefinitionData data, MarshallerData marshallerData);

    static int getStaticBufferSize(int marshalledParametersCount, boolean marshalledResult) {
        int slots = marshalledParametersCount != 0 ? marshalledParametersCount : marshalledResult ? 1 : 0;
        return slots * BYTES_PER_PARAMETER;
    }

    final void writeSourceFile(DefinitionData data, String content) throws IOException {
        TypeElement originatingElement = (TypeElement) data.annotatedType.asElement();
        String sourceFileFQN = String.format("%s.%s",
                        getEnclosingPackageElement(originatingElement).getQualifiedName().toString(), data.getTargetClassSimpleName());
        JavaFileObject sourceFile = parser.processor.env().getFiler().createSourceFile(sourceFileFQN, originatingElement);
        try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
            out.print(content);
        }
    }

    final FactoryMethodInfo generateStartPointFactory(CodeBuilder builder, DefinitionData data,
                    Collection<? extends DeclaredType> jniConfigProviderTypes, CharSequence startPointClassName,
                    CharSequence factoryMethodName, CodeBuilder.Parameter... additionalRequiredParameters) {
        List<CodeBuilder.Parameter> parameters = new ArrayList<>(data.annotatedTypeConstructorParams.size() + 1);
        List<CodeBuilder.Parameter> superParameters = new ArrayList<>(data.annotatedTypeConstructorParams.size());
        CodeBuilder.Parameter jniConfigProvider = null;
        CodeBuilder.Parameter jniConfig = null;
        List<CodeBuilder.Parameter> requiredList = new ArrayList<>();
        Collections.addAll(requiredList, additionalRequiredParameters);
        for (VariableElement ve : data.annotatedTypeConstructorParams) {
            TypeMirror parameterType = ve.asType();
            CodeBuilder.Parameter parameter = CodeBuilder.newParameter(parameterType, ve.getSimpleName());
            if (isAssignableToAny(parameterType, jniConfigProviderTypes)) {
                jniConfigProvider = parameter;
            } else if (types.isSameType(typeCache.jniConfig, parameterType)) {
                jniConfig = parameter;
            }
            requiredList.removeIf((required) -> types.isSameType(required.type, parameterType));
            parameters.add(parameter);
            superParameters.add(parameter);
        }
        parameters.addAll(requiredList);
        // `factoryMethodsParameters` differs from `parameters` by missing
        // `SuppressWarnings("unused")` annotation on `jniConfig`.
        List<CodeBuilder.Parameter> factoryMethodsParameters = new ArrayList<>(parameters);
        if (jniConfig == null && jniConfigProvider == null) {
            CharSequence[] annotations;
            if (data.getAllCustomMarshallers().isEmpty()) {
                annotations = new CharSequence[]{new CodeBuilder(builder).annotation(typeCache.suppressWarnings, "unused").build()};
            } else {
                annotations = new CharSequence[0];
            }
            jniConfig = CodeBuilder.newParameter(typeCache.jniConfig, "config", annotations);
        }
        builder.methodStart(EnumSet.of(Modifier.STATIC), factoryMethodName, data.annotatedType,
                        factoryMethodsParameters, Collections.emptyList());
        builder.indent();
        builder.lineStart("return ").newInstance(startPointClassName, parameterNames(parameters)).lineEnd(";");
        builder.dedent();
        builder.line("}");
        return new FactoryMethodInfo(parameters, superParameters, jniConfig, jniConfigProvider, requiredList);
    }

    private boolean isAssignableToAny(TypeMirror type, Collection<? extends DeclaredType> targetTypes) {
        for (DeclaredType targetType : targetTypes) {
            if (types.isAssignable(type, targetType)) {
                return true;
            }
        }
        return false;
    }

    final void generateMarshallerLookups(CodeBuilder builder, DefinitionData data) {
        for (MarshallerData marshaller : data.getAllCustomMarshallers()) {
            List<CharSequence> params = new ArrayList<>();
            if (types.isSameType(marshaller.forType, types.erasure(marshaller.forType))) {
                params.add(new CodeBuilder(builder).classLiteral(marshaller.forType).build());
            } else {
                params.add(new CodeBuilder(builder).typeLiteral(marshaller.forType).build());
            }
            for (AnnotationMirror annotationType : marshaller.annotations) {
                params.add(new CodeBuilder(builder).classLiteral(annotationType.getAnnotationType()).build());
            }
            builder.lineStart().write(marshaller.name).write(" = ");
            builder.invoke("config", "lookupMarshaller", params.toArray(new CharSequence[0])).lineEnd(";");
        }
    }

    final CacheSnippets cacheSnippets(DefinitionData data) {
        if (data.hasCustomDispatch()) {
            return CacheSnippets.customDispatch(types, typeCache);
        } else {
            return CacheSnippets.standardDispatch(types, typeCache);
        }
    }

    final CodeBuilder overrideMethod(CodeBuilder builder, DefinitionData data, MethodData methodData) {
        for (AnnotationMirror mirror : methodData.element.getAnnotationMirrors()) {
            if (!Utilities.contains(data.ignoreAnnotations, mirror.getAnnotationType(), types) &&
                            !Utilities.contains(data.marshallerAnnotations, mirror.getAnnotationType(), types)) {
                builder.lineStart().annotation(mirror.getAnnotationType(), null).lineEnd("");
            }
        }
        return overrideMethod(builder, methodData.element, methodData.type);
    }

    final CodeBuilder overrideMethod(CodeBuilder builder, ExecutableElement methodElement, ExecutableType methodType) {
        builder.lineStart().annotation(typeCache.override, null).lineEnd("");
        Set<Modifier> newModifiers = EnumSet.copyOf(methodElement.getModifiers());
        newModifiers.remove(Modifier.ABSTRACT);
        builder.methodStart(newModifiers, methodElement.getSimpleName(),
                        methodType.getReturnType(),
                        CodeBuilder.newParameters(methodElement.getParameters(), methodType.getParameterTypes()),
                        methodType.getThrownTypes());
        return builder;
    }

    static TypeMirror jniTypeForJavaType(TypeMirror javaType, Types types, AbstractTypeCache cache) {
        if (javaType.getKind().isPrimitive() || javaType.getKind() == TypeKind.VOID) {
            return javaType;
        }
        TypeMirror erasedType = types.erasure(javaType);
        switch (erasedType.getKind()) {
            case DECLARED:
                if (types.isSameType(cache.string, javaType)) {
                    return cache.jString;
                } else if (types.isSameType(cache.clazz, javaType)) {
                    return cache.jClass;
                } else if (types.isSubtype(javaType, cache.throwable)) {
                    return cache.jThrowable;
                } else {
                    return cache.jObject;
                }
            case ARRAY:
                TypeMirror componentType = ((ArrayType) erasedType).getComponentType();
                switch (componentType.getKind()) {
                    case BOOLEAN:
                        return cache.jBooleanArray;
                    case BYTE:
                        return cache.jByteArray;
                    case CHAR:
                        return cache.jCharArray;
                    case SHORT:
                        return cache.jShortArray;
                    case INT:
                        return cache.jIntArray;
                    case LONG:
                        return cache.jLongArray;
                    case FLOAT:
                        return cache.jFloatArray;
                    case DOUBLE:
                        return cache.jDoubleArray;
                    default:
                        throw new UnsupportedOperationException("Not supported for array of " + componentType.getKind());
                }
            default:
                throw new UnsupportedOperationException("Not supported for " + javaType.getKind());
        }
    }

    static boolean isParameterizedType(TypeMirror type) {
        switch (type.getKind()) {
            case DECLARED:
                return !((DeclaredType) type).getTypeArguments().isEmpty();
            case ARRAY:
                return isParameterizedType(((ArrayType) type).getComponentType());
            case INTERSECTION: {
                boolean res = false;
                for (TypeMirror t : ((IntersectionType) type).getBounds()) {
                    res |= isParameterizedType(t);
                }
                return res;
            }
            case TYPEVAR:
            case WILDCARD:
                return true;
            case UNION: {
                boolean res = false;
                for (TypeMirror t : ((UnionType) type).getAlternatives()) {
                    res |= isParameterizedType(t);
                }
                return res;
            }
            default:
                return false;
        }
    }

    void generateMarshallerFields(CodeBuilder builder, DefinitionData data, Modifier... modifiers) {
        for (MarshallerData marshaller : data.getAllCustomMarshallers()) {
            Set<Modifier> modSet = EnumSet.noneOf(Modifier.class);
            Collections.addAll(modSet, modifiers);
            builder.lineStart().writeModifiers(modSet).space().parameterizedType(typeCache.binaryMarshaller, marshaller.forType).space().write(marshaller.name).lineEnd(";");
        }
    }

    static void generateCacheFields(CodeBuilder builder, HotSpotToNativeBridgeGenerator.CacheSnippets cacheSnippets, DefinitionData data) {
        for (AbstractBridgeParser.MethodData methodData : data.toGenerate) {
            AbstractBridgeParser.CacheData cacheData = methodData.cachedData;
            if (cacheData != null) {
                Set<Modifier> modifiers = EnumSet.of(Modifier.PRIVATE);
                modifiers.addAll(cacheSnippets.modifiers(methodData.cachedData));
                builder.lineStart().writeModifiers(modifiers).space().write(cacheSnippets.entryType(builder, cacheData)).space().write(cacheData.cacheFieldName).lineEnd(";");
            }
        }
    }

    static void generateCacheFieldsInit(CodeBuilder builder, CacheSnippets cacheSnippets, DefinitionData data) {
        for (AbstractBridgeParser.MethodData methodData : data.toGenerate) {
            AbstractBridgeParser.CacheData cacheData = methodData.cachedData;
            if (cacheData != null) {
                CharSequence cacheFieldInit = cacheSnippets.initializeCacheField(builder, cacheData);
                if (cacheFieldInit != null) {
                    builder.lineStart("this.").write(cacheData.cacheFieldName).write(" = ").write(cacheFieldInit).lineEnd(";");
                }
            }
        }
    }

    void generateSizeEstimate(CodeBuilder builder, CharSequence targetVar, List<Map.Entry<MarshallerData, CharSequence>> customMarshallers) {
        builder.lineStart().write(types.getPrimitiveType(TypeKind.INT)).space().write(targetVar).write(" = ");
        boolean first = true;
        for (Map.Entry<MarshallerData, CharSequence> e : customMarshallers) {
            if (first) {
                first = false;
            } else {
                builder.spaceIfNeeded().write("+").space();
            }
            builder.invoke(e.getKey().name, "inferSize", e.getValue());
        }
        builder.lineEnd(";");
    }

    static PackageElement getEnclosingPackageElement(TypeElement typeElement) {
        return (PackageElement) typeElement.getEnclosingElement();
    }

    static CharSequence[] parameterNames(List<? extends CodeBuilder.Parameter> parameters) {
        return parameters.stream().map((p) -> p.name).toArray(CharSequence[]::new);
    }

    abstract static class MarshallerSnippets {

        private final AbstractTypeCache cache;
        final MarshallerData marshallerData;
        final Types types;

        MarshallerSnippets(MarshallerData marshallerData, Types types, AbstractTypeCache cache) {
            this.marshallerData = marshallerData;
            this.types = types;
            this.cache = cache;
        }

        @SuppressWarnings("unused")
        Set<CharSequence> getEndPointSuppressedWarnings(CodeBuilder currentBuilder, TypeMirror type) {
            return Collections.emptySet();
        }

        abstract TypeMirror getEndPointMethodParameterType(TypeMirror type);

        abstract CharSequence marshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence formalParameter, CharSequence marshalledParametersOutput,
                        CharSequence jniEnvFieldName);

        abstract CharSequence unmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput,
                        CharSequence jniEnvFieldName);

        @SuppressWarnings("unused")
        boolean preMarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName,
                        Map<String, CharSequence> parameterValueOverrides) {
            return false;
        }

        @SuppressWarnings("unused")
        boolean preUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName,
                        Map<String, CharSequence> parameterValueOverride) {
            return false;
        }

        @SuppressWarnings("unused")
        void postMarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName, CharSequence resultVariableName) {
        }

        @SuppressWarnings("unused")
        void postUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName, CharSequence resultVariableName) {
        }

        @SuppressWarnings("unused")
        CharSequence preMarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence jniEnvFieldName) {
            return null;
        }

        @SuppressWarnings("unused")
        CharSequence preUnmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence jniEnvFieldName) {
            return null;
        }

        abstract CharSequence marshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence marshalledResultOutput, CharSequence jniEnvFieldName);

        abstract CharSequence unmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence marshalledResultInput,
                        CharSequence jniEnvFieldName);

        static CharSequence outArrayLocal(CharSequence parameterName) {
            return parameterName + "Out";
        }

        final boolean isArrayWithDirectionModifiers(TypeMirror parameterType) {
            return parameterType.getKind() == TypeKind.ARRAY && !marshallerData.annotations.isEmpty();
        }

        final AnnotationMirror findIn(List<? extends AnnotationMirror> annotations) {
            return find(annotations, cache.in);
        }

        final AnnotationMirror findOut(List<? extends AnnotationMirror> annotations) {
            return find(annotations, cache.out);
        }

        final CharSequence unmarshallHotSpotToNativeProxyInNative(CodeBuilder builder, TypeMirror parameterType, CharSequence parameterName, DefinitionData data) {
            TypeMirror receiverType = marshallerData.useCustomReceiverAccessor ? data.customReceiverAccessor.getParameters().get(0).asType() : parameterType;
            CharSequence classLiteral = new CodeBuilder(builder).classLiteral(receiverType).build();
            CodeBuilder result = new CodeBuilder(builder).invokeStatic(cache.nativeObjectHandles, "resolve", parameterName, classLiteral);
            if (marshallerData.useCustomReceiverAccessor) {
                result = new CodeBuilder(result).invokeStatic(data.annotatedType, data.customReceiverAccessor.getSimpleName(), result.build());
            }
            return result.build();
        }

        final CharSequence unmarshallNativeToHotSpotProxyInNative(CodeBuilder builder, CharSequence parameterName, CharSequence jniEnvFieldName) {
            List<CharSequence> args = Arrays.asList(jniEnvFieldName, parameterName);
            boolean hasGeneratedFactory = !marshallerData.annotations.isEmpty();
            boolean isHSObject = types.isSubtype(marshallerData.forType, cache.hSObject);
            if (hasGeneratedFactory && !isHSObject) {
                DeclaredType receiverType = (DeclaredType) marshallerData.nonDefaultReceiver.asType();
                List<CharSequence> newArgs = new ArrayList<>();
                newArgs.add(new CodeBuilder(builder).newInstance(receiverType, args.toArray(new CharSequence[0])).build());
                newArgs.add(jniEnvFieldName);
                args = newArgs;
            }
            CharSequence proxy = createProxy(builder, NativeToHotSpotBridgeGenerator.START_POINT_FACTORY_NAME, args);
            if (marshallerData.customDispatchFactory != null) {
                CodeBuilder factory = new CodeBuilder(builder).invokeStatic((DeclaredType) marshallerData.customDispatchFactory.getEnclosingElement().asType(),
                                marshallerData.customDispatchFactory.getSimpleName(), proxy);
                proxy = factory.build();
            }
            CodeBuilder result = new CodeBuilder(builder);
            result.invoke(parameterName, "isNonNull").write(" ? ").write(proxy).write(" : ").write("null");
            return result.build();
        }

        final CharSequence unmarshallHotSpotToNativeProxyInHotSpot(CodeBuilder builder, CharSequence parameterName, CharSequence currentIsolateSnippet) {
            List<CharSequence> args = Arrays.asList(currentIsolateSnippet, parameterName);
            boolean hasGeneratedFactory = !marshallerData.annotations.isEmpty();
            boolean isNativeObject = types.isSubtype(marshallerData.forType, cache.nativeObject);
            if (hasGeneratedFactory && !isNativeObject) {
                args = Collections.singletonList(new CodeBuilder(builder).newInstance(cache.nativeObject, args.toArray(new CharSequence[0])).build());
            }
            CharSequence proxy = createProxy(builder, HotSpotToNativeBridgeGenerator.START_POINT_FACTORY_NAME, args);
            if (marshallerData.customDispatchFactory != null) {
                CodeBuilder factory = new CodeBuilder(builder).invokeStatic((DeclaredType) marshallerData.customDispatchFactory.getEnclosingElement().asType(),
                                marshallerData.customDispatchFactory.getSimpleName(), proxy);
                proxy = factory.build();
            }
            CodeBuilder result = new CodeBuilder(builder);
            result.write(parameterName).write(" != 0L ? ").write(proxy).write(" : ").write("null");
            return result.build();
        }

        final CharSequence unmarshallNativeToHotSpotProxyInHotSpot(CodeBuilder builder, TypeMirror parameterType, CharSequence parameterName, DefinitionData data) {
            TypeMirror receiverType = marshallerData.useCustomReceiverAccessor ? data.customReceiverAccessor.getParameters().get(0).asType() : parameterType;
            CharSequence result = parameterName;
            if (!types.isSubtype(parameterType, receiverType)) {
                result = new CodeBuilder(builder).cast(receiverType, parameterName).build();
            }
            if (marshallerData.useCustomReceiverAccessor) {
                result = new CodeBuilder(builder).invokeStatic(data.annotatedType, data.customReceiverAccessor.getSimpleName(), result).build();
            }
            return result;
        }

        private CharSequence createProxy(CodeBuilder builder, CharSequence factoryMethod, List<CharSequence> args) {
            boolean hasGeneratedFactory = !marshallerData.annotations.isEmpty();
            if (hasGeneratedFactory) {
                CharSequence type = new CodeBuilder(builder).write(types.erasure(marshallerData.forType)).write("Gen").build();
                return new CodeBuilder(builder).invoke(type,
                                factoryMethod, args.toArray(new CharSequence[0])).build();
            } else {
                return new CodeBuilder(builder).newInstance((DeclaredType) types.erasure(marshallerData.forType),
                                args.toArray(new CharSequence[0])).build();
            }
        }

        final CharSequence marshallHotSpotToNativeProxyInNative(CodeBuilder builder, CharSequence parameterName) {
            return new CodeBuilder(builder).invokeStatic(cache.nativeObjectHandles, "create", parameterName).build();
        }

        final CharSequence marshallNativeToHotSpotProxyInNative(CodeBuilder builder, CharSequence parameterName) {
            CodeBuilder receiver;
            if (types.isSubtype(marshallerData.forType, cache.hSObject)) {
                receiver = new CodeBuilder(builder).cast(cache.hSObject, parameterName, true);
            } else {
                CharSequence cast = new CodeBuilder(builder).cast(marshallerData.forType, parameterName).build();
                receiver = new CodeBuilder(builder).memberSelect(cast, marshallerData.nonDefaultReceiver.getSimpleName(), true);
            }
            return new CodeBuilder(builder).write(parameterName).write(" != null ? ").invoke(receiver.build(), "getHandle").write(" : ").invokeStatic(cache.wordFactory, "nullPointer").build();
        }

        final CharSequence marshallHotSpotToNativeProxyInHotSpot(CodeBuilder builder, CharSequence parameterName) {
            CodeBuilder receiver;
            if (types.isSubtype(marshallerData.forType, cache.nativeObject)) {
                receiver = new CodeBuilder(builder).write("((").write(cache.nativeObject).write(")").write(parameterName).write(")");
            } else {
                CharSequence cast = new CodeBuilder(builder).cast(marshallerData.forType, parameterName).build();
                receiver = new CodeBuilder(builder).memberSelect(cast, marshallerData.nonDefaultReceiver.getSimpleName(), true);
            }
            return new CodeBuilder(builder).write(parameterName).write(" != null ? ").invoke(receiver.build(), "getHandle").write(" : 0L").build();
        }

        static boolean trimToResult(AnnotationMirror annotation) {
            Boolean value = (Boolean) AbstractBridgeParser.getAnnotationValue(annotation, "trimToResult");
            return value != null && value;
        }

        static CharSequence getArrayLengthParameterName(AnnotationMirror annotation) {
            return (CharSequence) AbstractBridgeParser.getAnnotationValue(annotation, "arrayLengthParameter");
        }

        static CharSequence getArrayOffsetParameterName(AnnotationMirror annotation) {
            return (CharSequence) AbstractBridgeParser.getAnnotationValue(annotation, "arrayOffsetParameter");
        }

        private AnnotationMirror find(List<? extends AnnotationMirror> annotations, DeclaredType requiredAnnotation) {
            for (AnnotationMirror annotation : annotations) {
                if (types.isSameType(annotation.getAnnotationType(), requiredAnnotation)) {
                    return annotation;
                }
            }
            return null;
        }
    }

    abstract static class CacheSnippets {

        final Types types;
        final AbstractTypeCache cache;

        CacheSnippets(Types type, AbstractTypeCache cache) {
            this.types = type;
            this.cache = cache;
        }

        abstract CharSequence entryType(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData);

        abstract Set<Modifier> modifiers(AbstractBridgeParser.CacheData cacheData);

        abstract CharSequence initializeCacheField(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData);

        abstract CharSequence readCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver);

        abstract CharSequence writeCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver, CharSequence value);

        static CacheSnippets standardDispatch(Types types, AbstractTypeCache cache) {
            return new StandardDispatch(types, cache);
        }

        static CacheSnippets customDispatch(Types types, AbstractTypeCache cache) {
            return new CustomDispatch(types, cache);
        }

        private static final class StandardDispatch extends CacheSnippets {

            StandardDispatch(Types types, AbstractTypeCache cache) {
                super(types, cache);
            }

            @Override
            CharSequence entryType(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData) {
                return new CodeBuilder(currentBuilder).write(cacheData.cacheEntryType).build();
            }

            @Override
            Set<Modifier> modifiers(AbstractBridgeParser.CacheData cacheData) {
                return EnumSet.of(Modifier.VOLATILE);
            }

            @Override
            CharSequence initializeCacheField(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData) {
                return null;    // No initialisation code is needed
            }

            @Override
            CharSequence readCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver) {
                return cacheField;
            }

            @Override
            CharSequence writeCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver, CharSequence value) {
                return new CodeBuilder(currentBuilder).write(cacheField).write(" = ").write(value).build();
            }
        }

        private static final class CustomDispatch extends CacheSnippets {

            CustomDispatch(Types type, AbstractTypeCache cache) {
                super(type, cache);
            }

            @Override
            CharSequence entryType(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData) {
                return new CodeBuilder(currentBuilder).parameterizedType(cache.map, cache.object, cacheData.cacheEntryType).build();
            }

            @Override
            Set<Modifier> modifiers(AbstractBridgeParser.CacheData cacheData) {
                return EnumSet.of(Modifier.FINAL);
            }

            @Override
            CharSequence initializeCacheField(CodeBuilder currentBuilder, AbstractBridgeParser.CacheData cacheData) {
                CodeBuilder map = new CodeBuilder(currentBuilder).newInstance((DeclaredType) types.erasure(cache.weakHashMap),
                                Arrays.asList(cache.object, cacheData.cacheEntryType));
                return new CodeBuilder(currentBuilder).invokeStatic(cache.collections, "synchronizedMap", map.build()).build();
            }

            @Override
            CharSequence readCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver) {
                return new CodeBuilder(currentBuilder).invoke(cacheField, "get", receiver).build();
            }

            @Override
            CharSequence writeCache(CodeBuilder currentBuilder, CharSequence cacheField, CharSequence receiver, CharSequence value) {
                return new CodeBuilder(currentBuilder).invoke(cacheField, "put", receiver, value).build();
            }
        }
    }

    static final class CodeBuilder {

        private static final int INDENT_SIZE = 4;
        private static final Comparator<TypeElement> FQN_COMPARATOR = Comparator.comparing(a -> a.getQualifiedName().toString());

        private final CodeBuilder parent;
        private final PackageElement pkg;
        private final Types types;
        private final AbstractTypeCache typeCache;
        private final Collection<TypeElement> toImport;
        private final StringBuilder body;
        private int indentLevel;
        private Scope scope;

        CodeBuilder(PackageElement pkg, Types types, AbstractTypeCache typeCache) {
            this(null, pkg, types, typeCache, new TreeSet<>(FQN_COMPARATOR), new StringBuilder(), null);
        }

        CodeBuilder(CodeBuilder parent) {
            this(parent, parent.pkg, parent.types, parent.typeCache, parent.toImport, new StringBuilder(), parent.scope);
        }

        private CodeBuilder(CodeBuilder parent, PackageElement pkg, Types types, AbstractTypeCache typeCache, Collection<TypeElement> toImport, StringBuilder body, Scope scope) {
            this.parent = parent;
            this.pkg = pkg;
            this.types = types;
            this.typeCache = typeCache;
            this.toImport = toImport;
            this.body = body;
            this.scope = scope;
        }

        CodeBuilder indent() {
            indentLevel++;
            return this;
        }

        CodeBuilder dedent() {
            indentLevel--;
            return this;
        }

        CodeBuilder classStart(Set<Modifier> modifiers, CharSequence name, DeclaredType superClass, List<DeclaredType> superInterfaces) {
            scope = new Scope(superClass != null ? superClass : typeCache.object, superInterfaces, scope);
            lineStart();
            writeModifiers(modifiers).spaceIfNeeded().write("class ").write(name);
            if (superClass != null) {
                write(" extends ").write(superClass);
            }
            if (!superInterfaces.isEmpty()) {
                write(" implements ");
                for (Iterator<DeclaredType> it = superInterfaces.iterator(); it.hasNext();) {
                    write(it.next());
                    if (it.hasNext()) {
                        write(", ");
                    }
                }
            }
            lineEnd(" {");
            return this;
        }

        CodeBuilder classEnd() {
            scope = scope.parent;
            return line("}");
        }

        CodeBuilder methodStart(Set<Modifier> modifiers, CharSequence name, TypeMirror returnType,
                        List<? extends Parameter> params, List<? extends TypeMirror> exceptions) {
            lineStart();
            writeModifiers(modifiers).spaceIfNeeded();
            if (returnType != null) {
                write(returnType).space();
            }
            if (name != null) {
                write(name).write("(");
                for (Iterator<? extends Parameter> it = params.iterator(); it.hasNext();) {
                    Parameter param = it.next();
                    for (CharSequence annotation : param.annotations) {
                        write(annotation).space();
                    }
                    write(param.type).space().write(param.name);
                    if (it.hasNext()) {
                        write(", ");
                    }
                }
                write(")");
            }
            if (!exceptions.isEmpty()) {
                write(" throws ");
                for (Iterator<? extends TypeMirror> it = exceptions.iterator(); it.hasNext();) {
                    write(it.next());
                    if (it.hasNext()) {
                        write(", ");
                    }
                }
            }
            if (modifiers.contains(Modifier.ABSTRACT) || modifiers.contains(Modifier.NATIVE)) {
                lineEnd(";");
            } else {
                lineEnd(" {");
            }
            return this;
        }

        CodeBuilder call(CharSequence methodName, CharSequence... args) {
            write(methodName).write("(");
            for (int i = 0; i < args.length; i++) {
                write(args[i]);
                if ((i + 1) < args.length) {
                    write(", ");
                }
            }
            return write(")");
        }

        CodeBuilder newArray(TypeMirror componentType, CharSequence length) {
            return write("new ").write(componentType).write("[").write(length).write("]");
        }

        CodeBuilder newInstance(DeclaredType type, CharSequence... args) {
            return newInstance(new CodeBuilder(this).write(type).build(), Collections.emptyList(), args);
        }

        CodeBuilder newInstance(DeclaredType type, List<TypeMirror> actualTypeParameters, CharSequence... args) {
            return newInstance(new CodeBuilder(this).write(type).build(), actualTypeParameters, args);
        }

        CodeBuilder newInstance(CharSequence type, CharSequence... args) {
            return newInstance(type, Collections.emptyList(), args);
        }

        CodeBuilder newInstance(CharSequence type, List<TypeMirror> actualTypeParameters, CharSequence... args) {
            write("new ").write(type);
            if (!actualTypeParameters.isEmpty()) {
                write("<");
                for (Iterator<TypeMirror> it = actualTypeParameters.iterator(); it.hasNext();) {
                    write(it.next());
                    if (it.hasNext()) {
                        write(", ");
                    }
                }
                write(">");
            }
            write("(");
            for (int i = 0; i < args.length; i++) {
                write(args[i]);
                if ((i + 1) < args.length) {
                    write(", ");
                }
            }
            return write(")");
        }

        CodeBuilder invoke(CharSequence receiver, CharSequence methodName, CharSequence... args) {
            if (receiver != null) {
                write(receiver).write(".");
            }
            return call(methodName, args);
        }

        CodeBuilder invokeStatic(DeclaredType receiver, CharSequence methodName, CharSequence... args) {
            return write(types.erasure(receiver)).write(".").call(methodName, args);
        }

        CodeBuilder memberSelect(CharSequence receiver, CharSequence memberName, boolean brackets) {
            if (receiver != null) {
                if (brackets) {
                    write("(");
                }
                write(receiver);
                if (brackets) {
                    write(")");
                }
                write(".");
            }
            return write(memberName);
        }

        CodeBuilder parameterizedType(DeclaredType parameterizedType, TypeMirror... actualTypeParameters) {
            write(types.erasure(parameterizedType));
            write("<");
            for (int i = 0; i < actualTypeParameters.length; i++) {
                write(actualTypeParameters[i]);
                if (i + 1 < actualTypeParameters.length) {
                    write(", ");
                }
            }
            return write(">");
        }

        CodeBuilder annotation(DeclaredType type, Object value) {
            write("@").write(type);
            if (value != null) {
                write("(").writeAnnotationAttributeValue(value).write(")");
            }
            return this;
        }

        CodeBuilder annotationWithAttributes(DeclaredType type, Map<? extends CharSequence, Object> attributes) {
            write("@").write(type);
            if (!attributes.isEmpty()) {
                write("(");
                for (Iterator<? extends Map.Entry<? extends CharSequence, Object>> it = attributes.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<? extends CharSequence, Object> e = it.next();
                    write(e.getKey()).write(" = ").writeAnnotationAttributeValue(e.getValue());
                    if (it.hasNext()) {
                        write(", ");
                    }
                }
                write(")");
            }
            return this;
        }

        CodeBuilder classLiteral(TypeMirror type) {
            return write(types.erasure(type)).write(".class");
        }

        CodeBuilder typeLiteral(TypeMirror type) {
            return newInstance((DeclaredType) types.erasure(typeCache.typeLiteral), Collections.singletonList(type)).write("{}");
        }

        CodeBuilder cast(TypeMirror type, CharSequence value) {
            return cast(type, value, false);
        }

        CodeBuilder cast(TypeMirror type, CharSequence value, boolean brackets) {
            if (brackets) {
                write("(");
            }
            write("(").write(type).write(")").space().write(value);
            if (brackets) {
                write(")");
            }
            return this;
        }

        CodeBuilder writeAnnotationAttributeValue(Object value) {
            if (value.getClass() == String.class) {
                write('"' + (String) value + '"');
            } else if (value instanceof DeclaredType) {
                classLiteral((DeclaredType) value);
            } else if (value.getClass().isArray()) {
                write("{");
                int len = Array.getLength(value);
                for (int i = 0; i < len; i++) {
                    writeAnnotationAttributeValue(Array.get(value, i));
                    if ((i + 1) < len) {
                        write(", ");
                    }
                }
                write("}");
            } else {
                write(String.valueOf(value));
            }
            return this;
        }

        CodeBuilder writeDefaultValue(TypeMirror type) {
            switch (types.erasure(type).getKind()) {
                case VOID:
                    throw new IllegalArgumentException("The void type does not have default value.");
                case BOOLEAN:
                    write("false");
                    break;
                case BYTE:
                case CHAR:
                case INT:
                case LONG:
                case SHORT:
                    write("0");
                    break;
                case DOUBLE:
                    write("0.0d");
                    break;
                case FLOAT:
                    write("0.0f");
                    break;
                case DECLARED:
                    write("null");
                    break;
            }
            return this;
        }

        CodeBuilder writeModifiers(Set<Modifier> modifiers) {
            if (modifiers.contains(Modifier.ABSTRACT)) {
                write("abstract");
            }
            if (modifiers.contains(Modifier.PRIVATE)) {
                spaceIfNeeded();
                write("private");
            }
            if (modifiers.contains(Modifier.PROTECTED)) {
                spaceIfNeeded();
                write("protected");
            }
            if (modifiers.contains(Modifier.PUBLIC)) {
                spaceIfNeeded();
                write("public");
            }
            if (modifiers.contains(Modifier.STATIC)) {
                spaceIfNeeded();
                write("static");
            }
            if (modifiers.contains(Modifier.SYNCHRONIZED)) {
                spaceIfNeeded();
                write("synchronized");
            }
            if (modifiers.contains(Modifier.NATIVE)) {
                spaceIfNeeded();
                write("native");
            }
            if (modifiers.contains(Modifier.VOLATILE)) {
                spaceIfNeeded();
                write("volatile");
            }
            if (modifiers.contains(Modifier.FINAL)) {
                spaceIfNeeded();
                write("final");
            }
            return this;
        }

        CodeBuilder lineStart(CharSequence text) {
            return write(new String(new char[indentLevel * INDENT_SIZE]).replace('\0', ' ')).write(text);
        }

        CodeBuilder emptyLine() {
            return lineEnd("");
        }

        CodeBuilder lineStart() {
            return lineStart("");
        }

        CodeBuilder lineEnd(CharSequence text) {
            return write(text).write("\n");
        }

        CodeBuilder line(CharSequence line) {
            return lineStart(line).lineEnd("");
        }

        CodeBuilder write(CharSequence str) {
            body.append(str);
            return this;
        }

        CodeBuilder write(TypeElement te) {
            Element teEnclosing = te.getEnclosingElement();
            if (!teEnclosing.equals(pkg) && !isJavaLang(teEnclosing) && !isElementVisible(te)) {
                toImport.add(te);
            }
            return write(te.getSimpleName());
        }

        private boolean isElementVisible(TypeElement te) {
            return scope != null && scope.isElementVisible(te, types);
        }

        private static boolean isJavaLang(Element element) {
            return element.getKind() == ElementKind.PACKAGE && ((PackageElement) element).getQualifiedName().contentEquals("java.lang");
        }

        CodeBuilder write(TypeMirror type) {
            switch (type.getKind()) {
                case ARRAY:
                    write(((ArrayType) type).getComponentType()).write("[]");
                    break;
                case BOOLEAN:
                    write("boolean");
                    break;
                case BYTE:
                    write("byte");
                    break;
                case CHAR:
                    write("char");
                    break;
                case DECLARED:
                    DeclaredType declaredType = ((DeclaredType) type);
                    List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
                    if (!typeArguments.isEmpty()) {
                        parameterizedType(declaredType, typeArguments.toArray(new TypeMirror[0]));
                    } else {
                        write((TypeElement) declaredType.asElement());
                    }
                    break;
                case DOUBLE:
                    write("double");
                    break;
                case FLOAT:
                    write("float");
                    break;
                case INT:
                    write("int");
                    break;
                case LONG:
                    write("long");
                    break;
                case SHORT:
                    write("short");
                    break;
                case VOID:
                    write("void");
                    break;
                case WILDCARD:
                    write("?");
                    break;
            }
            return this;
        }

        CodeBuilder space() {
            return write(" ");
        }

        CodeBuilder spaceIfNeeded() {
            if (body.length() > 0 && !Character.isSpaceChar(body.charAt(body.length() - 1))) {
                write(" ");
            }
            return this;
        }

        String build() {
            if (parent == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("package ").append(pkg.getQualifiedName()).append(";\n\n");
                for (TypeElement typeElement : toImport) {
                    sb.append("import ").append(typeElement.getQualifiedName()).append(";\n");
                }
                sb.append("\n");
                sb.append(body);
                return sb.toString();
            } else {
                return body.toString();
            }
        }

        static Parameter newParameter(TypeMirror type, CharSequence name, CharSequence... annotations) {
            return new Parameter(type, name, annotations);
        }

        static List<? extends Parameter> newParameters(List<? extends VariableElement> params,
                        List<? extends TypeMirror> parameterTypes) {
            if (params.size() != parameterTypes.size()) {
                throw new IllegalArgumentException(String.format("params.size(%d) != parameterTypes.size(%d)",
                                params.size(), parameterTypes.size()));
            }
            List<Parameter> result = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                result.add(newParameter(parameterTypes.get(i), params.get(i).getSimpleName()));
            }
            return result;
        }

        static final class Parameter {

            final TypeMirror type;
            final CharSequence name;
            final CharSequence[] annotations;

            private Parameter(TypeMirror type, CharSequence name, CharSequence[] annotations) {
                this.type = type;
                this.name = name;
                this.annotations = annotations;
            }
        }

        private static final class Scope {

            private final Scope parent;
            private final DeclaredType superClass;
            private final List<DeclaredType> superInterfaces;

            Scope(DeclaredType superClass, List<DeclaredType> superInterfaces, Scope parent) {
                this.superClass = superClass;
                this.superInterfaces = superInterfaces;
                this.parent = parent;
            }

            boolean isElementVisible(TypeElement type, Types types) {
                Element owner = type.getEnclosingElement();
                if (owner.getKind().isClass() || owner.getKind().isInterface()) {
                    return isElementVisibleImpl((TypeElement) owner, types);
                }
                return false;
            }

            private boolean isElementVisibleImpl(TypeElement owner, Types types) {
                if (isInherited(owner, (TypeElement) ((DeclaredType) types.erasure(superClass)).asElement(), types)) {
                    return true;
                }
                for (DeclaredType superInterface : superInterfaces) {
                    if (isInherited(owner, (TypeElement) ((DeclaredType) types.erasure(superInterface)).asElement(), types)) {
                        return true;
                    }
                }
                if (parent != null) {
                    return parent.isElementVisibleImpl(owner, types);
                }
                return false;
            }

            private static boolean isInherited(TypeElement toCheck, TypeElement type, Types types) {
                if (toCheck.equals(type)) {
                    return true;
                }
                TypeMirror superClz = type.getSuperclass();
                if (superClz.getKind() != TypeKind.NONE) {
                    if (isInherited(toCheck, (TypeElement) ((DeclaredType) types.erasure(superClz)).asElement(), types)) {
                        return true;
                    }
                }
                for (TypeMirror superIfc : type.getInterfaces()) {
                    if (isInherited(toCheck, (TypeElement) ((DeclaredType) types.erasure(superIfc)).asElement(), types)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    static final class FactoryMethodInfo {

        final List<CodeBuilder.Parameter> parameters;
        final List<CodeBuilder.Parameter> superCallParameters;
        final CodeBuilder.Parameter jniConfigParameter;
        final CodeBuilder.Parameter jniConfigProvider;
        final List<CodeBuilder.Parameter> addedRequiredParameters;

        FactoryMethodInfo(List<CodeBuilder.Parameter> parameters, List<CodeBuilder.Parameter> superCallParameters,
                        CodeBuilder.Parameter jniConfigParameter, CodeBuilder.Parameter nativeIsolate,
                        List<CodeBuilder.Parameter> addedRequiredParameters) {
            this.parameters = parameters;
            this.superCallParameters = superCallParameters;
            this.jniConfigParameter = jniConfigParameter;
            this.jniConfigProvider = nativeIsolate;
            this.addedRequiredParameters = addedRequiredParameters;
        }
    }
}
