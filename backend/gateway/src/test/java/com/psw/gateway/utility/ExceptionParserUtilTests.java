package com.psw.gateway.utility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionParserUtilTests {
    private final ExceptionParserUtil parser = new ExceptionParserUtil();

    @Test
    void nullExceptionProducesEmptyChainWithoutFailureFlags() {
        Map<String, Object> result = parser.exceptionParser(null);

        assertThat(chain(result)).isEmpty();
        assertThat(result).containsEntry("depth", 0)
                .containsEntry("truncated", false)
                .containsEntry("circularReference", false);
    }

    @Test
    void parsesNormalExceptionWithStackTrace() {
        Exception exception = new IllegalArgumentException("bad input");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.psw.ExampleService", "load", "ExampleService.java", 42),
                new StackTraceElement("com.psw.ExampleController", "read", "ExampleController.java", 17)
        });

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(result).containsEntry("depth", 1)
                .containsEntry("truncated", false)
                .containsEntry("circularReference", false);
        assertThat(chain(result).getFirst())
                .containsEntry("depth", 0)
                .containsEntry("type", "IllegalArgumentException")
                .containsEntry("className", "java.lang.IllegalArgumentException")
                .containsEntry("message", "bad input")
                .containsEntry("sourceClass", "com.psw.ExampleService")
                .containsEntry("sourceMethod", "load")
                .containsEntry("sourceFile", "ExampleService.java")
                .containsEntry("lineNumber", 42)
                .containsEntry("stackTraceAvailable", true);
    }

    @Test
    void parsesNestedCausesInOrder() {
        Exception exception = new RuntimeException("outer",
                new IllegalStateException("middle", new Exception("inner")));

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("outer", "middle", "inner");
        assertThat(chain(result)).extracting(item -> item.get("depth"))
                .containsExactly(0, 1, 2);
        assertThat(result).containsEntry("depth", 3)
                .containsEntry("truncated", false)
                .containsEntry("circularReference", false);
    }

    @Test
    void includesErrorCauseInExceptionChain() {
        Exception exception = new RuntimeException("outer", new AssertionError("assertion failed"));

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("outer", "assertion failed");
        assertThat(chain(result).get(1)).containsEntry("depth", 1)
                .containsEntry("type", "AssertionError")
                .containsEntry("className", "java.lang.AssertionError");
        assertThat(result).containsEntry("depth", 2)
                .containsEntry("truncated", false)
                .containsEntry("circularReference", false);
    }

    @Test
    void preservesExactlyFourCausesWithoutMarkingChainAsTruncated() {
        Exception exception = new RuntimeException("outer",
                new IllegalStateException("second",
                        new IllegalArgumentException("third", new Exception("inner"))));

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("outer", "second", "third", "inner");
        assertThat(result).containsEntry("depth", 4)
                .containsEntry("truncated", false)
                .containsEntry("circularReference", false);
    }

    @Test
    void truncatesCauseChainAfterFourEntries() {
        Exception exception = new Exception("five");
        exception = new Exception("four", exception);
        exception = new Exception("three", exception);
        exception = new Exception("two", exception);
        exception = new Exception("one", exception);

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("one", "two", "three", "four");
        assertThat(result).containsEntry("depth", 4)
                .containsEntry("truncated", true)
                .containsEntry("circularReference", false);
    }

    @Test
    void handlesEmptyStackTraceAndNullMessage() {
        Exception exception = new Exception((String) null);
        exception.setStackTrace(new StackTraceElement[0]);

        Map<String, Object> parsed = chain(parser.exceptionParser(exception)).getFirst();

        assertThat(parsed).containsEntry("message", null)
                .containsEntry("stackTraceAvailable", false)
                .doesNotContainKeys("sourceClass", "sourceMethod", "sourceFile", "lineNumber");
    }

    @Test
    void protectsAgainstCircularCauseGraph() {
        CircularException first = new CircularException("first");
        CircularException second = new CircularException("second");
        first.cause = second;
        second.cause = first;

        Map<String, Object> result = parser.exceptionParser(first);

        assertThat(chain(result)).hasSize(2);
        assertThat(result).containsEntry("circularReference", true)
                .containsEntry("truncated", false);
    }

    @Test
    void detectsCycleAtDepthLimitWithoutMarkingChainAsTruncated() {
        CircularException first = new CircularException("first");
        CircularException second = new CircularException("second");
        CircularException third = new CircularException("third");
        CircularException fourth = new CircularException("fourth");
        first.cause = second;
        second.cause = third;
        third.cause = fourth;
        fourth.cause = first;

        Map<String, Object> result = parser.exceptionParser(first);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("first", "second", "third", "fourth");
        assertThat(result).containsEntry("depth", 4)
                .containsEntry("circularReference", true)
                .containsEntry("truncated", false);
    }

    @Test
    void doesNotTreatEqualButDistinctCausesAsCircularReferences() {
        Exception exception = new EqualException("outer", new EqualException("inner", null));

        Map<String, Object> result = parser.exceptionParser(exception);

        assertThat(chain(result)).extracting(item -> item.get("message"))
                .containsExactly("outer", "inner");
        assertThat(result).containsEntry("depth", 2)
                .containsEntry("circularReference", false)
                .containsEntry("truncated", false);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chain(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("chain");
    }

    private static final class CircularException extends Exception {
        private Throwable cause;

        private CircularException(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }

    private static final class EqualException extends Exception {
        private EqualException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualException;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
