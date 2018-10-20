inline fun String.ifEmpty(defaultValue: () -> String): String =
        if (isEmpty()) defaultValue() else this
