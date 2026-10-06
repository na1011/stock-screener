// ReaderVersionPinTest(f9a1d4c7)의 정규화기 넷을 그대로 옮겨 경계 입력을 먹인다. 사용: java NormalizerProbe.java
import java.util.*;

public class NormalizerProbe {

    static String methodAt(String code, int start) {
        int depth = 0;
        boolean seenBody = false;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(code, i);
                continue;
            }
            if (c == '{') {
                depth++;
                seenBody = true;
            } else if (c == '}') {
                depth--;
                if (seenBody && depth == 0) {
                    return code.substring(start, i + 1);
                }
            }
        }
        throw new IllegalStateException("메서드 끝을 못 찾았다");
    }

    static int skipLiteral(String code, int i) {
        char quote = code.charAt(i);
        if (quote == '"' && code.startsWith("\"\"\"", i)) {
            int end = code.indexOf("\"\"\"", i + 3);
            return end < 0 ? code.length() - 1 : end + 2;
        }
        for (int j = i + 1; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j;
            }
        }
        return code.length() - 1;
    }

    static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipLiteral(code, i);
                out.append(code, i, end + 1);
                i = end;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end - 1;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String collapse(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    static String norm(String code) {
        return collapse(stripComments(code));
    }

    static void pair(String label, String a, String b, boolean shouldDiffer) {
        boolean differ = !norm(a).equals(norm(b));
        System.out.printf("%-58s 다름=%-5s 기대=%-5s %s%n", label, differ, shouldDiffer, differ == shouldDiffer ? "OK" : "**어긋남**");
    }

    public static void main(String[] args) {
        // 1. 문자열 안의 주석 표지 뒤 코드 변경
        pair("문자열 안 /* 뒤의 코드 변경", "f(\"/*\"); a = 1; /* c */ b = 2;", "f(\"/*\"); a = 2; /* c */ b = 2;", true);
        pair("문자열 안 // 뒤의 코드 변경", "f(\"http://x\"); a = 1;\nb = 2;", "f(\"http://x\"); a = 1;\nb = 3;", true);
        // 2. 문자 리터럴
        pair("'\"' 뒤의 코드 변경", "c = '\"'; a = 1; // x\nb = 2;", "c = '\"'; a = 1; // y\nb = 3;", true);
        pair("'\\'' 뒤의 주석만 변경", "c = '\\''; a = 1; // x\nb = 2;", "c = '\\''; a = 1; // y\nb = 2;", false);
        pair("'\\\\' 뒤의 주석만 변경", "c = '\\\\'; a = 1; // x\nb = 2;", "c = '\\\\'; a = 1; // y\nb = 2;", false);
        // 3. 텍스트 블록
        pair("텍스트 블록 안 // 와 그 뒤 코드", "s = \"\"\"\n  a // b\n  \"\"\"; x = 1;", "s = \"\"\"\n  a // b\n  \"\"\"; x = 2;", true);
        pair("텍스트 블록 안 이스케이프 \\\"\"\" 뒤 코드 변경", "s = \"\"\"\n  q \\\"\"\" /* \n  \"\"\"; x = 1; /* k */ y = 1;",
                "s = \"\"\"\n  q \\\"\"\" /* \n  \"\"\"; x = 2; /* k */ y = 1;", true);
        // 4. 유니코드 이스케이프로 쓴 따옴표
        pair("\\u0022 로 연 문자열 안 // 뒤 코드 변경", "s = \\u0022a // b\\u0022; x = 1;", "s = \\u0022a // b\\u0022; x = 2;", true);
        // 5. 리터럴 안 공백
        pair("리터럴 안 공백 하나 → 둘", "split(\" \");", "split(\"  \");", true);
        pair("리터럴 안 공백 → 탭 문자", "split(\" \");", "split(\"\t\");", true);
        pair("텍스트 블록 들여쓰기 변경", "s = \"\"\"\n    a\n  b\n  \"\"\";", "s = \"\"\"\n  a\n  b\n  \"\"\";", true);
        // 6. 코드 공백만 · 주석만
        pair("코드 공백만(a+b → a + b)", "x = a+b;", "x = a + b;", false);
        pair("javadoc만 변경", "/** a */ int f() { return 1; }", "/** b */ int f() { return 1; }", false);
        // 7. methodAt — 첫 indexOf가 다른 자리를 잡는가
        String two = "static String pickInstance(List<String> n) { return null; }\n static String pickInstance(List<String> n, String c) { return n.get(0); }";
        System.out.println("오버로드 앞에 두면 잡는 몸통 = " + methodAt(stripComments(two), stripComments(two).indexOf("static String pickInstance(")));
    }
}
