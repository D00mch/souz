package com.dumch.tool

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class InputParamDescription(val value: String)

/**
 * [Input] should be a data class with all the properties annotated with the [InputParamDescription]
 * TODO: add compile time check for the above rule
 */
interface ToolSetup<Input> {

    val name: String
    val description: String

    operator fun invoke(input: Input): String
}

class BadInputException(msg: String) : Exception(msg)
