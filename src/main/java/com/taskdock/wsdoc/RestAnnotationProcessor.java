/*
 * Copyright (c) Taskdock, Inc. 2009-2010. All Rights Reserved.
 */

package com.taskdock.wsdoc;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.*;

@SupportedAnnotationTypes("com.taskdock.wsdoc.RestApiMountPoint")
public class RestAnnotationProcessor extends AbstractProcessor {

    private RestDocumentation _docs = new RestDocumentation();

    @Override
    public boolean process(Set<? extends TypeElement> supportedAnnotations, RoundEnvironment roundEnvironment) {
        for (Element e : roundEnvironment.getElementsAnnotatedWith(RequestMapping.class)) {
            if (e instanceof ExecutableElement) {
                processRequestMappingMethod((ExecutableElement) e);
            }
        }
        _docs.writePlainText(System.out);
        return true;
    }

    private void processRequestMappingMethod(ExecutableElement executableElement) {
        TypeElement cls = (TypeElement) executableElement.getEnclosingElement();
        String path = getClassLevelUrlPath(cls);

        RequestMapping anno = executableElement.getAnnotation(RequestMapping.class);
        path = addMethodPathComponent(executableElement, cls, path, anno);
        RequestMethod meth = getRequestMethod(executableElement, cls, anno);

        RestDocumentation.Resource.Method doc = _docs.getResourceDocumentation(path).getMethodDocumentation(meth);
        buildParameterData(executableElement, doc);
        buildResponseFormat(executableElement.getReturnType(), doc);
    }

    private void buildParameterData(ExecutableElement executableElement, RestDocumentation.Resource.Method doc) {

        // only process @RequestBody and @PathVariable parameters for now.
        // TODO Consider expanding this to include other Spring REST annotations.
        List<VariableElement> requestBodies = new ArrayList<VariableElement>();
        List<VariableElement> pathVars = new ArrayList<VariableElement>();
        for (VariableElement var : executableElement.getParameters()) {
            if (var.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class) != null)
                requestBodies.add(var);
            if (var.getAnnotation(PathVariable.class) != null)
                pathVars.add(var);
        }

        if (pathVars.size() > 0)
            buildPathVariables(pathVars, doc);

        if (requestBodies.size() > 1)
            throw new IllegalStateException(String.format(
                "Method %s in class %s has multiple @RequestBody params",
                    executableElement.getSimpleName(), executableElement.getEnclosingElement()));

        if (requestBodies.size() == 1)
            buildRequestBody(requestBodies.get(0), doc);
    }

    private void buildRequestBody(VariableElement var, RestDocumentation.Resource.Method doc) {
        RestDocumentation.Resource.Method.RequestBody bodyDoc = doc.getRequestBodyDocumentation();
        bodyDoc.setJsonValue(newJsonType(var.asType()));
    }

    private void buildPathVariables(List<VariableElement> pathVars, RestDocumentation.Resource.Method doc) {
        RestDocumentation.Resource.Method.UrlSubstitutions subs = doc.getUrlSubstitutions();
        for (VariableElement pathVar : pathVars) {
            subs.addSubstitution(pathVar.getSimpleName().toString(), newJsonType(pathVar.asType()));
        }
    }

    private JsonType newJsonType(TypeMirror typeMirror) {
        if (isJsonPrimitive(typeMirror)) {
            return new JsonPrimitive(typeMirror.toString());
        } else if (typeMirror.getKind() == TypeKind.DECLARED) {
            // some sort of object... walk it
            DeclaredType type = (DeclaredType) typeMirror;
            return newJsonType(type, type.getTypeArguments());
        } else {
            throw new UnsupportedOperationException(typeMirror.toString());
        }
    }

    /**
     * Create a new JSON type for the given declared type. The caller is responsible for
     * providing a list of concrete types to use to replace parameterized type placeholders.
     */
    private JsonType newJsonType(DeclaredType type, List<? extends TypeMirror> concreteTypes) {
        TypeVisitorImpl visitor = new TypeVisitorImpl((TypeElement) type.asElement(), concreteTypes);
        return type.accept(visitor, null);
    }

    private boolean isJsonPrimitive(TypeMirror typeMirror) {
        return (typeMirror.getKind().isPrimitive()
            || typeMirror.toString().startsWith("java.lang")
            || typeMirror.toString().startsWith("java.util.Date")
            || typeMirror.toString().startsWith("org.joda"));
    }

    private void buildResponseFormat(TypeMirror type, RestDocumentation.Resource.Method doc) {
        // TODO write REST docs provided in some sort of annotation or comment
        doc.getResponseBody().setJsonValue(newJsonType(type));
    }

    private RequestMethod getRequestMethod(ExecutableElement executableElement, TypeElement cls, RequestMapping anno) {
        if (anno.method().length != 1)
            throw new IllegalStateException(String.format(
                "The RequestMapping annotation for %s.%s is not parseable. Exactly one method is required.",
                    cls.getQualifiedName(), executableElement.getSimpleName()));
        else
            return anno.method()[0];
    }

    private String addMethodPathComponent(ExecutableElement executableElement, TypeElement cls, String path, RequestMapping anno) {
        if (anno == null || anno.value().length != 1)
            throw new IllegalStateException(String.format(
                "The RequestMapping annotation for %s.%s is not parseable. Exactly one value is required.",
                    cls.getQualifiedName(), executableElement.getSimpleName()));
        else
            return joinPaths(path, anno.value()[0]);
    }

    private String getClassLevelUrlPath(TypeElement cls) {
        RestApiMountPoint mountPoint = cls.getAnnotation(RestApiMountPoint.class);
        String path = mountPoint.value();

        RequestMapping clsAnno = cls.getAnnotation(RequestMapping.class);
        if (clsAnno == null || clsAnno.value().length == 0)
            return path;
        else if (clsAnno.value().length == 1)
            return joinPaths(path, clsAnno.value()[0]);
        else
            throw new IllegalStateException(String.format(
                "The RequestMapping annotation of class %s has multiple value strings. Only zero or one value is supported",
                    cls.getQualifiedName()));
    }

    private String joinPaths(String lhs, String rhs) {
        while (lhs.endsWith("/"))
            lhs = lhs.substring(0, lhs.length() - 1);

        while (rhs.startsWith("/"))
            rhs = rhs.substring(1);

        return lhs + "/" + rhs;
    }

    private class TypeVisitorImpl implements TypeVisitor<JsonType,Void> {
        private Map<Name, DeclaredType> _typeArguments = new HashMap();

        public TypeVisitorImpl(TypeElement type, List<? extends TypeMirror> typeArguments) {
            List<? extends TypeParameterElement> generics = type.getTypeParameters();
            for (int i = 0; i < generics.size(); i++) {
                _typeArguments.put(generics.get(i).getSimpleName(),
                    typeArguments.isEmpty() ? null : (DeclaredType) typeArguments.get(i));
            }
        }

        @Override
        public JsonType visit(TypeMirror typeMirror, Void o) {
            throw new UnsupportedOperationException(typeMirror.toString());
        }

        @Override
        public JsonType visit(TypeMirror typeMirror) {
            throw new UnsupportedOperationException(typeMirror.toString());
        }

        @Override
        public JsonType visitPrimitive(PrimitiveType primitiveType, Void o) {
            return newJsonType(primitiveType);
        }

        @Override
        public JsonType visitNull(NullType nullType, Void o) {
            throw new UnsupportedOperationException(nullType.toString());
        }

        @Override
        public JsonType visitArray(ArrayType arrayType, Void o) {
            throw new UnsupportedOperationException(arrayType.toString());
        }

        @Override
        public JsonType visitDeclared(DeclaredType declaredType, Void o) {
            if (isJsonPrimitive(declaredType)) {
                // 'primitive'-ish things
                return new JsonPrimitive(declaredType.toString());

            } else if (isInstanceOf(declaredType, Collection.class)) {

                if (declaredType.getTypeArguments().size() == 0) {
                    return new JsonArray(new JsonPrimitive(Object.class.getName()));
                }

                TypeMirror elem = declaredType.getTypeArguments().get(0);
                return new JsonArray(elem.accept(this, o));
                
            } else if (isInstanceOf(declaredType, Map.class)) {

                if (declaredType.getTypeArguments().size() == 0) {
                    return new JsonDict(
                        new JsonPrimitive(Object.class.getName()), new JsonPrimitive(Object.class.getName()));
                }

                TypeMirror key = declaredType.getTypeArguments().get(0);
                TypeMirror val = declaredType.getTypeArguments().get(1);
                return new JsonDict(key.accept(this, o), val.accept(this, o));

            } else {
                TypeElement element = (TypeElement) declaredType.asElement();
                if (element.getKind() == ElementKind.ENUM) {
                    List<String> enumConstants = new ArrayList();
                    for (Element e : element.getEnclosedElements()) {
                        if (e.getKind() == ElementKind.ENUM_CONSTANT) {
                            enumConstants.add(e.toString());
                        }
                    }
                    JsonPrimitive primitive = new JsonPrimitive(String.class.getName());  // TODO is this always a string?
                    primitive.setRestrictions(enumConstants);
                    return primitive;
                } else {
                    return buildType(element, o);
                }
            }
        }

        private boolean isInstanceOf(TypeMirror typeMirror, Class type) {
            if (!(typeMirror instanceof DeclaredType))
                return false;

            if (typeMirror.toString().startsWith(type.getName()))
                return true;

            DeclaredType declaredType = (DeclaredType) typeMirror;
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            for (TypeMirror iface : typeElement.getInterfaces()) {
                if (isInstanceOf(iface, type))
                    return true;
            }
            if (isInstanceOf(typeElement.getSuperclass(), type))
                return true;

            return false;
        }

        private JsonObject buildType(TypeElement element, Void o) {
            JsonObject json = new JsonObject();
            buildTypeContents(json, element);
            return json;
        }

        private void buildTypeContents(JsonObject o, TypeElement element) {
            DeclaredType sup = (DeclaredType) element.getSuperclass();
            if (!isJsonPrimitive(sup))
                buildTypeContents(o, (TypeElement) sup.asElement());

            for (Element e : element.getEnclosedElements()) {
                if (e instanceof ExecutableElement) {
                    addFieldFromBeanGetter(o, (ExecutableElement) e);
                }
            }
        }

        private void addFieldFromBeanGetter(JsonObject o, ExecutableElement executableElement) {
            if (!isJsonBeanGetter(executableElement))
                return;

            TypeMirror type = executableElement.getReturnType();
            String methodName = executableElement.getSimpleName().toString();
            String beanName = methodName.substring(4, methodName.length());
            beanName = methodName.substring(3, 4).toLowerCase() + beanName;

            // loop over the element's generic types, and build a concrete list from the owning context
            List<DeclaredType> concreteTypes = new ArrayList();

            // replace variables with the current concrete manifestation
            if (type instanceof TypeVariable) {
                type = getDeclaredTypeForTypeVariable((TypeVariable) type);
                if (type == null)
                    return; // couldn't find a replacement -- must be a generics-capable type with no generics info
            }

            if (type instanceof DeclaredType) {
                TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
                for (TypeParameterElement generic : element.getTypeParameters()) {
                    concreteTypes.add(_typeArguments.get(generic.getSimpleName()));
                }
                o.addField(beanName, newJsonType((DeclaredType) type, concreteTypes));
            } else {
                o.addField(beanName, newJsonType(type));
            }
        }

        private boolean isJsonBeanGetter(ExecutableElement executableElement) {
            if (executableElement.getKind() != ElementKind.METHOD)
                return false;

            if (executableElement.getReturnType().getKind() == TypeKind.NULL)
                return false;

            if (!(executableElement.getSimpleName().toString().startsWith("get")
                    && executableElement.getParameters().size() == 0))
                return false;

            if (executableElement.getAnnotation(JsonIgnore.class) != null)
                return false;

            return true;
        }

        @Override
        public JsonType visitError(ErrorType errorType, Void o) {
            throw new UnsupportedOperationException(errorType.toString());
        }

        @Override
        public JsonType visitTypeVariable(TypeVariable typeVariable, Void o) {
            DeclaredType type = getDeclaredTypeForTypeVariable(typeVariable);
            if (type != null) // null: un-parameterized usage of a generics-having type
                return type.accept(this, o);
            else
                return null;
        }

        private DeclaredType getDeclaredTypeForTypeVariable(TypeVariable typeVariable) {
            Name name = typeVariable.asElement().getSimpleName();
            if (!_typeArguments.containsKey(name)) {
                throw new UnsupportedOperationException(String.format(
                    "Unknown parameterized type: %s. Available types in this context: %s",
                        typeVariable.toString(), _typeArguments));
            } else {
                return _typeArguments.get(name);
            }
        }

        @Override
        public JsonType visitWildcard(WildcardType wildcardType, Void o) {
            throw new UnsupportedOperationException(wildcardType.toString());
        }

        @Override
        public JsonType visitExecutable(ExecutableType executableType, Void o) {
            throw new UnsupportedOperationException(executableType.toString());
        }

        @Override
        public JsonType visitNoType(NoType noType, Void o) {
            throw new UnsupportedOperationException(noType.toString());
        }

        @Override
        public JsonType visitUnknown(TypeMirror typeMirror, Void o) {
            throw new UnsupportedOperationException(typeMirror.toString());
        }
    }
}