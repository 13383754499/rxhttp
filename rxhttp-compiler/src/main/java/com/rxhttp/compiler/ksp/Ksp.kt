package com.rxhttp.compiler.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.rxhttp.compiler.K_ARRAY_TYPE
import com.rxhttp.compiler.K_TYPE
import com.rxhttp.compiler.isKps2
import com.squareup.javapoet.JavaFile
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BOOLEAN_ARRAY
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.CHAR_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.DOUBLE_ARRAY
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FLOAT_ARRAY
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LONG_ARRAY
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.SHORT_ARRAY
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.U_BYTE_ARRAY
import com.squareup.kotlinpoet.U_INT_ARRAY
import com.squareup.kotlinpoet.U_LONG_ARRAY
import com.squareup.kotlinpoet.U_SHORT_ARRAY
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import org.jetbrains.annotations.Nullable

/**
 * User: ljx
 * Date: 2021/11/11
 * Time: 17:38
 */

//获取对象类型，包名+类名
internal fun KSTypeReference.getQualifiedName() =
    resolve().declaration.qualifiedName?.asString()

//获取方法名
internal fun KSFunctionDeclaration.getFunName() = simpleName.asString()


/*

| 参数类型             | kapt (JTypeName -> KTypeName)                        | ksp (KTypeName)
| :---:               |  :---:                                               |   :---:
|  `int... a`         |  int[] -> kotlin.IntArray                            |   kotlin.IntArray & vararg
|  `String... a`      |  java.lang.String[] -> kotlin.Array<kotlin.String>   |   kotlin.Array<kotlin.String> & vararg
|  `int[]  a`         |  int[] -> kotlin.IntArray                            |   kotlin.IntArray
|  `String[] a`       |  java.lang.String[] -> kotlin.Array<kotlin.String>   |   kotlin.Array<kotlin.String>
|  `vararg a: Int`    |  int[] -> kotlin.IntArray                            |   kotlin.Int & vararg
|  `vararg a: String` |  java.lang.String[] -> kotlin.Array<kotlin.String>   |   kotlin.String & vararg
|  `a: IntArray`      |  int[] -> kotlin.IntArray                            |   kotlin.IntArray
|  `a: Array<String>` |  java.lang.String[] -> kotlin.Array<kotlin.String>   |   kotlin.Array<kotlin.String>

 */
@KspExperimental
internal fun KSValueParameter.toKParameterSpec(
    typeParamResolver: TypeParameterResolver = TypeParameterResolver.EMPTY
): ParameterSpec {
    val variableName = name!!.asString()
    val isNullable = getAnnotationsByType(Nullable::class).firstOrNull() != null
    var typeName = type.toTypeName(typeParamResolver)
    if (isVararg && isJava() && !isKps2) {
        typeName = when (typeName) {
            BOOLEAN_ARRAY -> BOOLEAN
            BYTE_ARRAY, U_BYTE_ARRAY -> BYTE
            CHAR_ARRAY -> CHAR
            SHORT_ARRAY, U_SHORT_ARRAY -> SHORT
            INT_ARRAY, U_INT_ARRAY -> INT
            LONG_ARRAY, U_LONG_ARRAY -> LONG
            FLOAT_ARRAY -> FLOAT
            DOUBLE_ARRAY -> DOUBLE
            is ParameterizedTypeName -> typeName.typeArguments.first()
            else -> typeName
        }
    }
    if (isNullable) typeName = typeName.copy(true)
    return ParameterSpec.builder(variableName, typeName).apply {
        if (isVararg)
            addModifiers(KModifier.VARARG)
        if (isNoInline)
            addModifiers(KModifier.NOINLINE)
        if (isCrossInline)
            addModifiers(KModifier.CROSSINLINE)
    }.build()
}

internal fun ClassName.parameterizedBy(vararg s: String) =
    parameterizedBy(s.map { TypeVariableName(it) })

internal fun KSNode.isJava() = origin == Origin.JAVA || origin == Origin.JAVA_LIB

internal fun KSNode.isKotlin() = origin == Origin.KOTLIN || origin == Origin.KOTLIN_LIB

internal fun KSClassDeclaration.superclass(): KSTypeReference? {
    return superTypes.find {
        val declaration = it.resolve().declaration
        (declaration as? KSClassDeclaration)?.classKind == ClassKind.CLASS
    }
}

//判断解析器构造方法是否有效
fun KSFunctionDeclaration.isValid(typeCount: Int): Boolean {
    //1、非public方法，无效
    if (!isPublic()) return false
    val parameters = parameters
    if (parameters.isEmpty()) {
        //2、构造方法没有参数，且泛型数量等于0，有效，反之无效
        return typeCount == 0
    }
    val firstParameter = parameters.first()
    val firstParameterType = firstParameter.type.toTypeName()
    if (firstParameterType == K_ARRAY_TYPE || (firstParameterType == K_TYPE && firstParameter.isVararg)) {
        //3、第一个参数为Type类型数组 或 Type类型可变参数, 有效
        return true
    }
    //4、构造方法参数数量小于泛型数量，无效
    if (parameters.size < typeCount) return false
    //5、构造方法前n个参数，皆为Type类型，有效  n为泛型数量
    return parameters.take(typeCount).all { K_TYPE == it.type.toTypeName() }
}

//获取onParser方法返回类型
fun KSClassDeclaration.findOnParserFunReturnType(): TypeName? {
    val ksFunction = getAllFunctions().find {
        it.isPublic() &&
                !it.modifiers.contains(Modifier.JAVA_STATIC) &&
                it.getFunName() == "onParse" &&
                it.parameters.size == 1 &&
                it.parameters[0].type.getQualifiedName() == "okhttp3.Response"
    }
    return ksFunction?.returnType?.toTypeName(typeParameters.toTypeParameterResolver())
}

/**
 * 获取Parser接口实际泛型类型
 * @param typeNameMap  key 类自身声明的泛型名称，如XxxParser<A, B>  value 子类(实现类)传递的真实泛型类型, 如 AParser<A> : XxxParser<User<A>, Book>
 */
fun KSClassDeclaration.getParserTypeParam(typeNameMap: Map<String, TypeName>? = null): TypeName? {
    val className = qualifiedName?.asString()
    val parserName = "rxhttp.wrapper.parse.Parser"
    val typeParserName = "rxhttp.wrapper.parse.TypeParser"
    val typeParameters = typeParameters
    if (parserName == className || typeParserName == className) {
        return typeNameMap?.get(typeParameters.first().name.asString())
    }
    val typeParamResolver = typeParameters.toTypeParameterResolver()
    for (superType in superTypes) {  //superTypes 包含父类及直接实现的接口
        val typeName = superType.toTypeName(typeParamResolver)  //引用的父类类型
        if (typeName == ANY) continue
        val ksDeclaration = superType.resolve().declaration  //声明的父类类型
        if (typeName is ParameterizedTypeName) {
            //将泛型类型转换为子类传递的类型
            val typeNames = typeName.typeArguments.map { it.convert(typeNameMap) }
            val ksTypeParameters = ksDeclaration.typeParameters //获取类自身声明的泛型列表
            var i = 0
            val newTypeNameMap = ksTypeParameters.associate {
                it.name.asString() to typeNames[i++]
            }
            val answer = (ksDeclaration as KSClassDeclaration).getParserTypeParam(newTypeNameMap)
            if (answer != null) {
                return answer
            }
        } else {
            (ksDeclaration as KSClassDeclaration).getParserTypeParam()
        }
    }
    return null
}

fun TypeName.convert(typeNameMap: Map<String, TypeName>? = null): TypeName {
    if (typeNameMap == null) return this
    return when (this) {
        is TypeVariableName -> {
            val typeName = typeNameMap[name]
            return typeName?.copy(typeName.isNullable || isNullable) ?: this
        }

        is ParameterizedTypeName -> {
            rawType.parameterizedBy(typeArguments.map { it.convert(typeNameMap) })
                .copy(isNullable)
        }

        else -> this
    }
}

fun ParameterSpec.isVararg() = modifiers.contains(KModifier.VARARG)

fun KSClassDeclaration.getPublicConstructors() = getConstructors().filter { it.isPublic() }

internal fun KSType.instanceOf(className: String, resolver: Resolver): Boolean {
    val ksClass = resolver.getClassDeclarationByName(className) ?: return false
    return ksClass.asStarProjectedType().isAssignableFrom(this)
}

@KspExperimental
internal fun KSPropertyDeclaration.isStaticToJava(): Boolean {
    return getAnnotationsByType(JvmField::class).firstOrNull() != null
            || Modifier.CONST in modifiers
}

internal fun FunSpec.Builder.addParameter(
    name: String,
    typeName: TypeName,
    nullable: Boolean = false,
    vararg modifiers: KModifier
) = addParameter(name, typeName.copy(nullable), *modifiers)

fun newParameterSpec(
    name: String,
    typeName: TypeName,
    nullable: Boolean = false,
    vararg modifiers: KModifier
) = ParameterSpec.builder(name, typeName.copy(nullable), *modifiers).build()

internal fun KSPropertyDeclaration.toMemberName(): MemberName {
    val className = (parent as? KSClassDeclaration)?.toClassName()
    val fieldName = simpleName.asString()
    return if (className != null) {
        MemberName(className, fieldName)
    } else {
        //kotlin top property
        MemberName(packageName.asString(), fieldName)
    }
}

internal fun KSPLogger.error(throwable: Throwable, ksNode: KSNode) {
    error(throwable.message ?: "", ksNode)
}


internal fun String.firstLetterUpperCase(): String {
    val charArray = toCharArray()
    val firstChar = charArray.firstOrNull() ?: return this
    if (firstChar.code in 97..122) {
        charArray[0] = firstChar.minus(32)
    }
    return String(charArray)
}

internal fun JavaFile.writeTo(
    codeGenerator: CodeGenerator,
    dependencies: Dependencies = Dependencies(false)
) {
    val fos = codeGenerator.createNewFile(dependencies, packageName, typeSpec.name, "java")
    fos.bufferedWriter(Charsets.UTF_8).use(this::writeTo)
}

internal fun getJvmName(name: String): AnnotationSpec {
    return AnnotationSpec.builder(JvmName::class)
        .addMember("\"$name\"")
        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
        .build()
}