package com.geno1024.ai.worm.core

/**
 * Minimal CLI parser: `--key value`, `--key=value`, switches, `-h` and positional
 * arguments. Callers declare pure switches up front so `--all --out x` works.
 */
class ArgParser(argv: Array<String>, flagNames: Set<String> = setOf("help")) {
    private val options = linkedMapOf<String, String>()
    private val switches = mutableSetOf<String>()
    private val flags = flagNames + "help"

    val positionals: List<String>

    init {
        val rest = mutableListOf<String>()
        var index = 0
        while (index < argv.size) {
            val token = argv[index]
            when {
                token == "--" -> {
                    rest += argv.drop(index + 1)
                    break
                }
                token == "-h" -> switches += "help"
                token.startsWith("--") -> {
                    val body = token.removePrefix("--")
                    val separator = body.indexOf('=')
                    val name = if (separator >= 0) body.substring(0, separator) else body
                    val next = argv.getOrNull(index + 1)
                    when {
                        separator >= 0 -> options[name] = body.substring(separator + 1)
                        name in flags -> switches += name
                        next != null && !next.startsWith("--") && next != "-h" -> {
                            options[name] = next
                            index++
                        }
                        else -> switches += name
                    }
                }
                else -> rest += token
            }
            index++
        }
        positionals = rest.toList()
    }

    fun has(name: String): Boolean = name in options || name in switches
    fun flag(name: String): Boolean = name in switches || options[name] == "true"
    fun string(name: String): String? = options[name]
    fun string(name: String, default: String): String = options[name] ?: default
    fun int(name: String, default: Int): Int = options[name]?.toIntOrNull() ?: default
    fun long(name: String, default: Long): Long = options[name]?.toLongOrNull() ?: default
}
