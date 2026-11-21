package com.gene.agent.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归下降的算术表达式求值器：+ - * / % ^、一元负号、括号、常用函数和常量。
 * 不用脚本引擎（JDK 15 起 Nashorn 已被删掉），自己写更能看懂计算器工具是怎么工作的。
 */
final class ExpressionEvaluator {

    private final String text;
    private int pos;

    private ExpressionEvaluator(String text) {
        this.text = text;
    }

    static double eval(String expression) {
        ExpressionEvaluator parser = new ExpressionEvaluator(expression.replace("×", "*").replace("÷", "/"));
        double value = parser.parseExpression();
        parser.skipSpaces();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("在 '" + parser.remaining() + "' 处有多余内容");
        }
        return value;
    }

    // expression := term (('+' | '-') term)*
    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            char operator = peek();
            if (operator == '+') {
                pos++;
                value += parseTerm();
            } else if (operator == '-') {
                pos++;
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    // term := unary (('*' | '/' | '%') unary)*
    private double parseTerm() {
        double value = parseUnary();
        while (true) {
            char operator = peek();
            switch (operator) {
                case '*' -> { pos++; value *= parseUnary(); }
                case '/' -> {
                    pos++;
                    double divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("除数为 0");
                    }
                    value /= divisor;
                }
                case '%' -> {
                    pos++;
                    double divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("取模的数为 0");
                    }
                    value %= divisor;
                }
                default -> {
                    return value;
                }
            }
        }
    }

    // unary := ('-' | '+') unary | power
    private double parseUnary() {
        char operator = peek();
        if (operator == '-') {
            pos++;
            return -parseUnary();
        }
        if (operator == '+') {
            pos++;
            return parseUnary();
        }
        return parsePower();
    }

    // power := atom ('^' unary)?  右结合，2^3^2 = 2^(3^2)
    private double parsePower() {
        double base = parseAtom();
        if (peek() == '^') {
            pos++;
            return Math.pow(base, parseUnary());
        }
        return base;
    }

    private double parseAtom() {
        skipSpaces();
        if (peek() == '(') {
            pos++;
            double value = parseExpression();
            expect(')');
            return value;
        }
        if (Character.isDigit(peek()) || peek() == '.') {
            return parseNumber();
        }
        String identifier = parseIdentifier();
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("期望数字，却遇到 '" + remaining() + "'");
        }
        if (peek() == '(') {
            pos++;
            List<Double> arguments = new ArrayList<>();
            if (peek() != ')') {
                do {
                    arguments.add(parseExpression());
                } while (matchArgSeparator());
            }
            expect(')');
            return apply(identifier, arguments);
        }
        return switch (identifier.toLowerCase()) {
            case "pi" -> Math.PI;
            case "e" -> Math.E;
            default -> throw new IllegalArgumentException("未知数字、常量或函数: " + identifier);
        };
    }

    private double parseNumber() {
        int start = pos;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
            pos++;
        }
        // 科学计数法 1.5e10
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')
                && pos + 1 < text.length()
                && (Character.isDigit(text.charAt(pos + 1)) || text.charAt(pos + 1) == '+' || text.charAt(pos + 1) == '-')) {
            pos += 2;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        String literal = text.substring(start, pos);
        try {
            return Double.parseDouble(literal);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无法解析数字: " + literal);
        }
    }

    private String parseIdentifier() {
        int start = pos;
        while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
            pos++;
        }
        return text.substring(start, pos);
    }

    private double apply(String name, List<Double> arguments) {
        double[] args = arguments.stream().mapToDouble(Double::doubleValue).toArray();
        String key = name.toLowerCase();
        return switch (key) {
            case "sqrt" -> {
                require(key, args, 1);
                if (args[0] < 0) {
                    throw new IllegalArgumentException("sqrt 不能对负数开方");
                }
                yield Math.sqrt(args[0]);
            }
            case "cbrt" -> { require(key, args, 1); yield Math.cbrt(args[0]); }
            case "abs" -> { require(key, args, 1); yield Math.abs(args[0]); }
            case "round" -> { require(key, args, 1); yield Math.round(args[0]) * 1.0; }
            case "floor" -> { require(key, args, 1); yield Math.floor(args[0]); }
            case "ceil" -> { require(key, args, 1); yield Math.ceil(args[0]); }
            case "ln" -> { require(key, args, 1); yield Math.log(args[0]); }
            case "log10" -> { require(key, args, 1); yield Math.log10(args[0]); }
            case "log" -> args.length == 2
                    ? Math.log(args[1]) / Math.log(args[0])
                    : Math.log10(args[0]);
            case "exp" -> { require(key, args, 1); yield Math.exp(args[0]); }
            case "sin" -> { require(key, args, 1); yield Math.sin(args[0]); }
            case "cos" -> { require(key, args, 1); yield Math.cos(args[0]); }
            case "tan" -> { require(key, args, 1); yield Math.tan(args[0]); }
            case "pow" -> { require(key, args, 2); yield Math.pow(args[0], args[1]); }
            case "min" -> { requireAtLeast(key, args, 2); yield arguments.stream().mapToDouble(Double::doubleValue).min().orElseThrow(); }
            case "max" -> { requireAtLeast(key, args, 2); yield arguments.stream().mapToDouble(Double::doubleValue).max().orElseThrow(); }
            default -> throw new IllegalArgumentException("不支持的函数: " + name);
        };
    }

    private void require(String name, double[] args, int arity) {
        if (args.length != arity) {
            throw new IllegalArgumentException(name + " 需要 " + arity + " 个参数，实际给了 " + args.length + " 个");
        }
    }

    private void requireAtLeast(String name, double[] args, int arity) {
        if (args.length < arity) {
            throw new IllegalArgumentException(name + " 至少需要 " + arity + " 个参数");
        }
    }

    private boolean matchArgSeparator() {
        skipSpaces();
        if (peek() == ',') {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        skipSpaces();
        if (peek() != expected) {
            throw new IllegalArgumentException("期望 '" + expected + "'，实际是 '" + remaining() + "'");
        }
        pos++;
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        skipSpaces();
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private boolean atEnd() {
        return pos >= text.length();
    }

    private String remaining() {
        return text.substring(pos);
    }
}
