package com.nanobot.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Schema validation")
class SchemaValidatorTest {

    @Nested
    @DisplayName("required fields")
    class RequiredFields {

        @Test
        @DisplayName("missing required field")
        void missingRequiredField() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string")
            ), "required", List.of("name"));
            var errors = Schema.validateJsonSchemaValue(Map.of(), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("missing required name"));
        }

        @Test
        @DisplayName("all required present — no errors")
        void allRequiredPresent() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string")
            ), "required", List.of("name"));
            var errors = Schema.validateJsonSchemaValue(Map.of("name", "test"), schema, "");
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("optional field missing — no errors")
        void optionalFieldMissing() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "count", Map.of("type", "integer")
            ), "required", List.of("name"));
            var errors = Schema.validateJsonSchemaValue(Map.of("name", "test"), schema, "");
            assertThat(errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("type checking")
    class TypeChecking {

        @Test
        @DisplayName("wrong type reported")
        void wrongType() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "count", Map.of("type", "integer")
            ), "required", List.of("count"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("count", "not_a_number"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("should be integer"));
        }

        @Test
        @DisplayName("correct types accepted")
        void correctTypes() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "count", Map.of("type", "integer")
            ), "required", List.of("name", "count"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("name", "hello", "count", 42), schema, "");
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("nullable type allows null")
        void nullableType() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "opt", Map.of("type", List.of("string", "null"))
            ), "required", List.of());
            var params = new java.util.HashMap<String, Object>();
            params.put("opt", null);
            var errors1 = Schema.validateJsonSchemaValue(params, schema, "");
            assertThat(errors1).isEmpty();

            var errors2 = Schema.validateJsonSchemaValue(
                    Map.of("opt", "hello"), schema, "");
            assertThat(errors2).isEmpty();
        }

        @Test
        @DisplayName("nullable via 'nullable' key")
        void nullableKey() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "opt", Map.of("type", "string", "nullable", true)
            ), "required", List.of());
            var params = new java.util.HashMap<String, Object>();
            params.put("opt", null);
            var errors = Schema.validateJsonSchemaValue(params, schema, "");
            assertThat(errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("enum constraint")
    class EnumConstraint {

        @Test
        @DisplayName("value in enum passes")
        void valueInEnum() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "color", Map.of("type", "string", "enum", List.of("red", "green", "blue"))
            ), "required", List.of("color"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("color", "red"), schema, "");
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("value not in enum fails")
        void valueNotInEnum() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "color", Map.of("type", "string", "enum", List.of("red", "green"))
            ), "required", List.of("color"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("color", "yellow"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("must be one of"));
        }
    }

    @Nested
    @DisplayName("numeric constraints")
    class NumericConstraints {

        @Test
        @DisplayName("minimum constraint")
        void minimum() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "age", Map.of("type", "integer", "minimum", 0)
            ), "required", List.of("age"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("age", -1), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("must be >="));
        }

        @Test
        @DisplayName("maximum constraint")
        void maximum() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "age", Map.of("type", "integer", "maximum", 120)
            ), "required", List.of("age"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("age", 999), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("must be <="));
        }

        @Test
        @DisplayName("within range passes")
        void withinRange() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "age", Map.of("type", "integer", "minimum", 0, "maximum", 120)
            ), "required", List.of("age"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("age", 30), schema, "");
            assertThat(errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("string constraints")
    class StringConstraints {

        @Test
        @DisplayName("minLength constraint")
        void minLength() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 3)
            ), "required", List.of("name"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("name", "ab"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("at least"));
        }

        @Test
        @DisplayName("maxLength constraint")
        void maxLength() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string", "maxLength", 5)
            ), "required", List.of("name"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("name", "too_long"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("at most"));
        }
    }

    @Nested
    @DisplayName("nested objects")
    class NestedObjects {

        @Test
        @DisplayName("validates nested properties")
        void validatesNested() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "user", Map.of("type", "object", "properties", Map.of(
                            "name", Map.of("type", "string")
                    ), "required", List.of("name"))
            ), "required", List.of("user"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("user", Map.of("name", 123)), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("should be string") && e.contains("user.name"));
        }

        @Test
        @DisplayName("nested required check")
        void nestedRequired() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "user", Map.of("type", "object", "properties", Map.of(
                            "name", Map.of("type", "string")
                    ), "required", List.of("name"))
            ), "required", List.of("user"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("user", Map.of()), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("missing required user.name"));
        }

        @Test
        @DisplayName("valid nested passes")
        void validNested() {
            var schema = Map.of("type", "object", "properties", Map.of(
                    "user", Map.of("type", "object", "properties", Map.of(
                            "name", Map.of("type", "string")
                    ), "required", List.of("name"))
            ), "required", List.of("user"));
            var errors = Schema.validateJsonSchemaValue(
                    Map.of("user", Map.of("name", "Alice")), schema, "");
            assertThat(errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("array validation")
    class ArrayValidation {

        @Test
        @DisplayName("minItems constraint")
        void minItems() {
            var schema = Map.of("type", "array", "items", Map.of("type", "string"),
                    "minItems", 2);
            var errors = Schema.validateJsonSchemaValue(List.of("a"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("must have at least"));
        }

        @Test
        @DisplayName("items type validation")
        void itemsValidation() {
            var schema = Map.of("type", "array", "items", Map.of("type", "integer"));
            var errors = Schema.validateJsonSchemaValue(
                    List.of(1, "bad", 3), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("should be integer"));
        }

        @Test
        @DisplayName("maxItems constraint")
        void maxItems() {
            var schema = Map.of("type", "array", "items", Map.of("type", "string"),
                    "maxItems", 2);
            var errors = Schema.validateJsonSchemaValue(
                    List.of("a", "b", "c"), schema, "");
            assertThat(errors).anyMatch(e -> e.contains("must be at most"));
        }
    }
}
