package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Objects;

public sealed interface RequirementExpression permits RequirementExpression.Atom, RequirementExpression.All, RequirementExpression.Any {
    String canonicalKey();

    record Atom(String id, String description) implements RequirementExpression {
        public Atom {
            id = Objects.requireNonNullElse(id, "unknown");
            description = description == null ? id : description;
        }

        @Override
        public String canonicalKey() {
            return "atom:" + id;
        }
    }

    record All(List<RequirementExpression> values) implements RequirementExpression {
        public All {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public String canonicalKey() {
            return "all:" + values.stream().map(RequirementExpression::canonicalKey).sorted().toList();
        }
    }

    record Any(List<RequirementExpression> values) implements RequirementExpression {
        public Any {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public String canonicalKey() {
            return "any:" + values.stream().map(RequirementExpression::canonicalKey).sorted().toList();
        }
    }
}
