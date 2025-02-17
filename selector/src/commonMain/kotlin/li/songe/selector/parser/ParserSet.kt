package li.songe.selector.parser

import li.songe.selector.ExtSyntaxError
import li.songe.selector.Selector
import li.songe.selector.data.BinaryExpression
import li.songe.selector.data.CompareOperator
import li.songe.selector.data.ConnectOperator
import li.songe.selector.data.ConnectSegment
import li.songe.selector.data.ConnectWrapper
import li.songe.selector.data.Expression
import li.songe.selector.data.LogicalExpression
import li.songe.selector.data.LogicalOperator
import li.songe.selector.data.PolynomialExpression
import li.songe.selector.data.PropertySegment
import li.songe.selector.data.PropertyWrapper

internal object ParserSet {
    val whiteCharParser = Parser("\u0020\t\r\n") { source, offset, prefix ->
        var i = offset
        var data = ""
        while (i < source.length && prefix.contains(source[i])) {
            data += source[i]
            i++
        }
        ParserResult(data, i - offset)
    }
    val whiteCharStrictParser = Parser("\u0020\t\r\n") { source, offset, prefix ->
        ExtSyntaxError.assert(source, offset, prefix, "whitespace")
        whiteCharParser(source, offset)
    }
    val nameParser =
        Parser("*1234567890qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM_") { source, offset, prefix ->
            var i = offset
            val s0 = source.getOrNull(i)
            if ((s0 != null) && !prefix.contains(s0)) {
                return@Parser ParserResult("")
            }
            ExtSyntaxError.assert(source, i, prefix, "*0-9a-zA-Z_")
            var data = source[i].toString()
            i++
            if (data == "*") { // 范匹配
                return@Parser ParserResult(data, i - offset)
            }
            val center = "1234567890qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM_."
            while (i < source.length) {
//                . 不能在开头和结尾
                if (data[i - offset - 1] == '.') {
                    ExtSyntaxError.assert(source, i, prefix, "[0-9a-zA-Z_]")
                }
                if (center.contains(source[i])) {
                    data += source[i]
                } else {
                    break
                }
                i++
            }
            ParserResult(data, i - offset)
        }

    val combinatorOperatorParser =
        Parser(ConnectOperator.allSubClasses.joinToString("") { it.key }) { source, offset, _ ->
            val operator = ConnectOperator.allSubClasses.find { subOperator ->
                source.startsWith(
                    subOperator.key, offset
                )
            } ?: ExtSyntaxError.throwError(source, offset, "ConnectOperator")
            ParserResult(operator, operator.key.length)
        }

    val integerParser = Parser("1234567890") { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix, "number")
        var s = ""
        while (i < source.length && prefix.contains(source[i])) {
            s += source[i]
            i++
        }
        ParserResult(s.toInt(), i - offset)
    }


    //    [+-][a][n[^b]]
    val monomialParser = Parser("+-1234567890n") { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix)
        /**
         * one of 1, -1
         */
        val signal = when (source[i]) {
            '+' -> {
                i++
                1
            }

            '-' -> {
                i++
                -1
            }

            else -> 1
        }
        i += whiteCharParser(source, i).length
        // [a][n[^b]]
        ExtSyntaxError.assert(source, i, integerParser.prefix + "n")
        val coefficient = if (integerParser.prefix.contains(source[i])) {
            val coefficientResult = integerParser(source, i)
            i += coefficientResult.length
            coefficientResult.data
        } else {
            1
        } * signal
        // [n[^b]]
        if (i < source.length && source[i] == 'n') {
            i++
            if (i < source.length && source[i] == '^') {
                i++
                val powerResult = integerParser(source, i)
                i += powerResult.length
                return@Parser ParserResult(Pair(powerResult.data, coefficient), i - offset)
            } else {
                return@Parser ParserResult(Pair(1, coefficient), i - offset)
            }
        } else {
            return@Parser ParserResult(Pair(0, coefficient), i - offset)
        }
    }

    //    ([+-][a][n[^b]] [+-][a][n[^b]])
    val polynomialExpressionParser = Parser("(0123456789n") { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix)
        val monomialResultList = mutableListOf<ParserResult<Pair<Int, Int>>>()
        when (source[i]) {
            '(' -> {
                i++
                i += whiteCharParser(source, i).length
                ExtSyntaxError.assert(source, i, monomialParser.prefix)
                while (source[i] != ')') {
                    if (monomialResultList.size > 0) {
                        ExtSyntaxError.assert(source, i, "+-")
                    }
                    val monomialResult = monomialParser(source, i)
                    monomialResultList.add(monomialResult)
                    i += monomialResult.length
                    i += whiteCharParser(source, i).length
                    if (i >= source.length) {
                        ExtSyntaxError.assert(source, i, ")")
                    }
                }
                i++
            }

            else -> {
                val monomialResult = monomialParser(source, i)
                monomialResultList.add(monomialResult)
                i += monomialResult.length
            }
        }
        val map = mutableMapOf<Int, Int>()
        monomialResultList.forEach { monomialResult ->
            val (power, coefficient) = monomialResult.data
            map[power] = (map[power] ?: 0) + coefficient
        }
        map.mapKeys { power ->
            if (power.key > 1) {
                ExtSyntaxError.throwError(source, offset, "power must be 0 or 1")
            }
        }
        ParserResult(PolynomialExpression(map[1] ?: 0, map[0] ?: 0), i - offset)
    }

    //    [+-><](a*n^b)
    val combinatorParser = Parser(combinatorOperatorParser.prefix) { source, offset, _ ->
        var i = offset
        val operatorResult = combinatorOperatorParser(source, i)
        i += operatorResult.length
        var expressionResult: ParserResult<PolynomialExpression>? = null
        if (i < source.length && polynomialExpressionParser.prefix.contains(source[i])) {
            expressionResult = polynomialExpressionParser(source, i)
            i += expressionResult.length
        }
        ParserResult(
            ConnectSegment(
                operatorResult.data, expressionResult?.data ?: PolynomialExpression()
            ), i - offset
        )
    }

    val attrOperatorParser =
        Parser(CompareOperator.allSubClasses.joinToString("") { it.key }) { source, offset, _ ->
            val operator = CompareOperator.allSubClasses.find { compareOperator ->
                source.startsWith(compareOperator.key, offset)
            } ?: ExtSyntaxError.throwError(source, offset, "CompareOperator")
            ParserResult(operator, operator.key.length)
        }
    val stringParser = Parser("`'\"") { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix)
        val startChar = source[i]
        i++
        if (i >= source.length) {
            ExtSyntaxError.throwError(source, i, "any char")
        }
        var data = ""
        while (source[i] != startChar) {
            if (i >= source.length - 1) {
                ExtSyntaxError.assert(source, i, startChar.toString())
                break
            }
            // https://www.rfc-editor.org/rfc/inline-errata/rfc7159.html
            if (source[i].code in 0x0000..0x001F) {
                ExtSyntaxError.throwError(source, i, "0-1f escape char")
            }
            if (source[i] == '\\') {
                i++
                ExtSyntaxError.assert(source, i)
                data += when (source[i]) {
                    '\\' -> '\\'
                    '\'' -> '\''
                    '"' -> '"'
                    '`' -> '`'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'b' -> '\b'
                    'x' -> {
                        repeat(2) {
                            i++
                            ExtSyntaxError.assert(source, i, "0123456789abcdefABCDEF")
                        }
                        source.substring(i - 2 + 1, i + 1).toInt(16).toChar()
                    }

                    'u' -> {
                        repeat(4) {
                            i++
                            ExtSyntaxError.assert(source, i, "0123456789abcdefABCDEF")
                        }
                        source.substring(i - 4 + 1, i + 1).toInt(16).toChar()
                    }

                    else -> {
                        ExtSyntaxError.throwError(source, i, "escape char")
                    }
                }
            } else {
                data += source[i]
            }
            i++
        }
        i++
        ParserResult(data, i - offset)
    }

    private val varPrefix = "_" + ('a'..'z').joinToString("") + ('A'..'Z').joinToString("")
    private val varStr = varPrefix + '.' + ('0'..'9').joinToString("")
    val propertyParser = Parser(varPrefix) { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix)
        var data = source[i].toString()
        i++
        while (i < source.length && varStr.contains(source[i])) {
            if (source[i] == '.') {
                ExtSyntaxError.assert(source, i + 1, prefix)
            }
            data += source[i]
            i++
        }
        ParserResult(data, i - offset)
    }

    val valueParser =
        Parser("tfn" + stringParser.prefix + integerParser.prefix) { source, offset, prefix ->
            var i = offset
            ExtSyntaxError.assert(source, i, prefix)
            val value: Any? = when (source[i]) {
                't' -> {
                    i++
                    "rue".forEach { c ->
                        ExtSyntaxError.assert(source, i, c.toString())
                        i++
                    }
                    true
                }

                'f' -> {
                    i++
                    "alse".forEach { c ->
                        ExtSyntaxError.assert(source, i, c.toString())
                        i++
                    }
                    false
                }

                'n' -> {
                    i++
                    "ull".forEach { c ->
                        ExtSyntaxError.assert(source, i, c.toString())
                        i++
                    }
                    null
                }

                in stringParser.prefix -> {
                    val s = stringParser(source, i)
                    i += s.length
                    s.data
                }

                in integerParser.prefix -> {
                    val n = integerParser(source, i)
                    i += n.length
                    n.data
                }

                else -> {
                    ExtSyntaxError.throwError(source, i, prefix)
                }
            }
            ParserResult(value, i - offset)
        }

    val binaryExpressionParser = Parser { source, offset, _ ->
        var i = offset
        val parserResult = propertyParser(source, i)
        i += parserResult.length
        i += whiteCharParser(source, i).length
        val operatorResult = attrOperatorParser(source, i)
        i += operatorResult.length
        i += whiteCharParser(source, i).length
        val valueResult = valueParser(source, i)
        i += valueResult.length
        ParserResult(
            BinaryExpression(
                parserResult.data, operatorResult.data, valueResult.data
            ), i - offset
        )
    }

    val logicalOperatorParser = Parser { source, offset, _ ->
        var i = offset
        i += whiteCharParser(source, i).length
        val operator = LogicalOperator.allSubClasses.find { logicalOperator ->
            source.startsWith(logicalOperator.key, offset)
        } ?: ExtSyntaxError.throwError(source, offset, "LogicalOperator")
        ParserResult(operator, operator.key.length)
    }


    //    a>1 && a>1 || a>1
//    (a>1 || a>1) && a>1
    fun expressionParser(source: String, offset: Int): ParserResult<Expression> {
        var i = offset
        i += whiteCharParser(source, i).length
//        [exp, ||, exp, &&, &&]
        val parserResults = mutableListOf<ParserResult<*>>()
        while (i < source.length && source[i] != ']' && source[i] != ')') {
            when (source[i]) {
                '(' -> {
                    if (parserResults.isNotEmpty()) {
                        val lastToken = parserResults.last()
                        if (lastToken.data !is LogicalOperator) {
                            var count = 0
                            while (i - 1 >= count && source[i - 1 - count] in whiteCharParser.prefix) {
                                count++
                            }
                            ExtSyntaxError.throwError(
                                source, i - count - lastToken.length, "LogicalOperator"
                            )
                        }
                    }
                    i++
                    parserResults.add(expressionParser(source, i).apply { i += length })
                    ExtSyntaxError.assert(source, i, ")")
                    i++
                }

                in "|&" -> {
                    parserResults.add(logicalOperatorParser(source, i).apply { i += length })
                    i += whiteCharParser(source, i).length
                    ExtSyntaxError.assert(source, i, "(" + propertyParser.prefix)
                }

                else -> {
                    parserResults.add(binaryExpressionParser(source, i).apply { i += length })
                }
            }
            i += whiteCharParser(source, i).length
        }
        if (parserResults.isEmpty()) {
            ExtSyntaxError.throwError(
                source, i - offset, "Expression"
            )
        }
        if (parserResults.size == 1) {
            return ParserResult(parserResults.first().data as Expression, i - offset)
        }

//        运算符优先级 && > ||
//        a && b || c -> ab || c
//        0 1  2 3  4 -> 0  1  2
        val tokens = parserResults.map { it.data }.toMutableList()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == LogicalOperator.AndOperator) {
                tokens[index] = LogicalExpression(
                    left = tokens[index - 1] as Expression,
                    operator = LogicalOperator.AndOperator,
                    right = tokens[index + 1] as Expression
                )
                tokens.removeAt(index - 1)
                tokens.removeAt(index + 1 - 1)
            } else {
                index++
            }
        }
        while (tokens.size > 1) {
            tokens[1] = LogicalExpression(
                left = tokens[0] as Expression,
                operator = tokens[1] as LogicalOperator.OrOperator,
                right = tokens[2] as Expression
            )
            tokens.removeAt(0)
            tokens.removeAt(2 - 1)
        }
        return ParserResult(tokens.first() as Expression, i - offset)
    }


    val attrParser = Parser("[") { source, offset, prefix ->
        var i = offset
        ExtSyntaxError.assert(source, i, prefix)
        i++
        i += whiteCharParser(source, i).length
        val exp = expressionParser(source, i)
        i += exp.length
        ExtSyntaxError.assert(source, i, "]")
        i++
        ParserResult(
            exp.data, i - offset
        )
    }


    val selectorUnitParser = Parser { source, offset, _ ->
        var i = offset
        var tracked = false
        if (source.getOrNull(i) == '@') {
            tracked = true
            i++
        }
        val nameResult = nameParser(source, i)
        i += nameResult.length
        val expressions = mutableListOf<Expression>()
        while (i < source.length && source[i] == '[') {
            val attrResult = attrParser(source, i)
            i += attrResult.length
            expressions.add(attrResult.data)
        }
        if (nameResult.length == 0 && expressions.size == 0) {
            ExtSyntaxError.throwError(source, i, "[")
        }
        ParserResult(PropertySegment(tracked, nameResult.data, expressions), i - offset)
    }

    val connectSelectorParser = Parser { source, offset, _ ->
        var i = offset
        i += whiteCharParser(source, i).length
        val topSelector = selectorUnitParser(source, i)
        i += topSelector.length
        val selectorList = mutableListOf<Pair<ConnectSegment, PropertySegment>>()
        while (i < source.length && whiteCharParser.prefix.contains(source[i])) {
            i += whiteCharStrictParser(source, i).length
            if (i >= source.length) {
                break
            }
            val combinator = if (combinatorParser.prefix.contains(source[i])) {
                val combinatorResult = combinatorParser(source, i)
                i += combinatorResult.length
                i += whiteCharStrictParser(source, i).length
                combinatorResult.data
            } else {
                ConnectSegment(polynomialExpression = PolynomialExpression(1, 0))
            }
            val selectorResult = selectorUnitParser(source, i)
            i += selectorResult.length
            selectorList.add(combinator to selectorResult.data)
        }
        ParserResult(topSelector.data to selectorList, i - offset)
    }

    val endParser = Parser { source, offset, _ ->
        if (offset != source.length) {
            ExtSyntaxError.throwError(source, offset, "end")
        }
        ParserResult(Unit, 0)
    }

    val selectorParser: (String) -> Selector = { source ->
        var i = 0
        i += whiteCharParser(source, i).length
        val combinatorSelectorResult = connectSelectorParser(source, i)
        i += combinatorSelectorResult.length

        i += whiteCharParser(source, i).length
        i += endParser(source, i).length
        val data = combinatorSelectorResult.data
        val propertySelectorList = mutableListOf<PropertySegment>()
        val combinatorSelectorList = mutableListOf<ConnectSegment>()
        propertySelectorList.add(data.first)
        data.second.forEach {
            propertySelectorList.add(it.second)
            combinatorSelectorList.add(it.first)
        }
        val wrapperList = mutableListOf(PropertyWrapper(propertySelectorList.first()))
        combinatorSelectorList.forEachIndexed { index, combinatorSelector ->
            val combinatorSelectorWrapper = ConnectWrapper(combinatorSelector, wrapperList.last())
            val propertySelectorWrapper =
                PropertyWrapper(propertySelectorList[index + 1], combinatorSelectorWrapper)
            wrapperList.add(propertySelectorWrapper)
        }
        Selector(wrapperList.last())
    }
}