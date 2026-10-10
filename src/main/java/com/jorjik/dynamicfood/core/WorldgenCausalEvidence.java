package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public record WorldgenCausalEvidence(List<Node> nodes, List<Relationship> relationships) {
    private static final Comparator<Node> NODE_ORDER = Comparator.comparing(Node::canonicalKey);
    private static final Comparator<Relationship> RELATIONSHIP_ORDER =
        Comparator.comparing(Relationship::canonicalKey);

    public WorldgenCausalEvidence {
        nodes = nodes.stream().distinct().sorted(NODE_ORDER).toList();
        relationships = relationships.stream().distinct().sorted(RELATIONSHIP_ORDER).toList();
        TreeSet<NodeRef> nodeRefs = new TreeSet<>(Comparator.comparing(NodeRef::canonicalKey));
        nodes.forEach(node -> nodeRefs.add(node.ref()));
        if (relationships.stream().anyMatch(relationship ->
            !nodeRefs.contains(relationship.from()) || !nodeRefs.contains(relationship.to()))) {
            throw new IllegalArgumentException("causal relationships must reference declared nodes");
        }
    }

    public static WorldgenCausalEvidence empty() {
        return new WorldgenCausalEvidence(List.of(), List.of());
    }

    public String canonicalKey() {
        StringBuilder key = new StringBuilder();
        append(key, Integer.toString(nodes.size()));
        nodes.forEach(node -> append(key, node.canonicalKey()));
        append(key, Integer.toString(relationships.size()));
        relationships.forEach(relationship -> append(key, relationship.canonicalKey()));
        return key.toString();
    }

    public String mechanicalIdentityKey() {
        StringBuilder key = new StringBuilder();
        List<String> identities = nodes.stream().map(node -> node.ref().canonicalKey()).distinct().sorted().toList();
        append(key, Integer.toString(identities.size()));
        identities.forEach(value -> append(key, value));
        List<String> edges = relationships.stream().map(Relationship::mechanicalIdentityKey).distinct().sorted()
            .toList();
        append(key, Integer.toString(edges.size()));
        edges.forEach(value -> append(key, value));
        return key.toString();
    }

    public boolean hasConflictingDefinitions() {
        return nodes.stream().collect(java.util.stream.Collectors.groupingBy(Node::ref,
                java.util.stream.Collectors.mapping(Node::rawEvidence, java.util.stream.Collectors.toSet())))
            .values().stream().anyMatch(definitions -> definitions.size() > 1);
    }

    public WorldgenCausalEvidence merge(WorldgenCausalEvidence other) {
        Objects.requireNonNull(other, "other causal evidence");
        List<Node> mergedNodes = new ArrayList<>(nodes);
        mergedNodes.addAll(other.nodes);
        List<Relationship> mergedRelationships = new ArrayList<>(relationships);
        mergedRelationships.addAll(other.relationships);
        return new WorldgenCausalEvidence(mergedNodes, mergedRelationships);
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public enum NodeType {
        BIOME,
        FEATURE_STEP,
        PLACED_FEATURE,
        CONFIGURED_FEATURE,
        FEATURE_COMPONENT,
        BLOCK_STATE,
        EXTRACTION_OPERATION,
        STRUCTURE_SET,
        STRUCTURE,
        STRUCTURE_PLACEMENT,
        BIOME_CONSTRAINT,
        BIOME_TAG,
        TEMPLATE_POOL,
        POOL_ELEMENT,
        POOL_ALIAS,
        STRUCTURE_TEMPLATE,
        CONTAINER,
        LOOT_TABLE,
        ITEM_OUTPUT,
        ITEM_OUTPUT_CANDIDATE,
        UNRESOLVED_MECHANIC
    }

    public enum RelationshipType {
        DECLARES_FEATURE_STEP,
        CONTAINS_PLACED_FEATURE,
        PLACES_CONFIGURED_FEATURE,
        NESTED_CONFIGURED_FEATURE,
        NESTED_PLACED_FEATURE,
        ALTERNATIVE_BRANCH,
        DEFAULT_BRANCH,
        CONDITIONAL_BRANCH,
        HAS_FEATURE_COMPONENT,
        MAY_GENERATE_BLOCK,
        CONTAINS_STRUCTURE,
        USES_STRUCTURE_PLACEMENT,
        USES_START_POOL,
        CONTAINS_POOL_ELEMENT,
        REFERENCES_TEMPLATE,
        REFERENCES_POOL,
        USES_FALLBACK_POOL,
        DECLARES_BIOME_CONSTRAINT,
        HAS_BIOME_MEMBER,
        DECLARES_POOL_ALIAS,
        CONTAINS_CONTAINER,
        UNRESOLVED,
        EXTRACTED_BY,
        USES_LOOT_TABLE,
        YIELDS_ITEM,
        CANDIDATE_OUTPUT
    }

    public enum BranchKind {
        NONE,
        ALTERNATIVE,
        DEFAULT,
        CONDITIONAL,
        ORDERED_CONDITIONAL,
        UNKNOWN
    }

    public record NodeRef(NodeType type, String identifier) {
        public NodeRef {
            Objects.requireNonNull(type, "node type");
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("causal node identifier is required");
            }
        }

        public String canonicalKey() {
            return encode(type.name(), identifier);
        }
    }

    public record Node(NodeRef ref, String sourceResource, String rawEvidence) {
        public Node {
            Objects.requireNonNull(ref, "node ref");
            sourceResource = Objects.requireNonNullElse(sourceResource, "");
            rawEvidence = Objects.requireNonNullElse(rawEvidence, "");
        }

        private String canonicalKey() {
            return encode(ref.canonicalKey(), sourceResource, rawEvidence);
        }
    }

    public record Relationship(
        NodeRef from,
        RelationshipType type,
        NodeRef to,
        BranchKind branchKind,
        String branchEvidence,
        String sourceResource,
        String unresolvedReason
    ) {
        public Relationship {
            Objects.requireNonNull(from, "relationship source");
            Objects.requireNonNull(type, "relationship type");
            Objects.requireNonNull(to, "relationship target");
            Objects.requireNonNull(branchKind, "branch kind");
            branchEvidence = Objects.requireNonNullElse(branchEvidence, "");
            sourceResource = Objects.requireNonNullElse(sourceResource, "");
            unresolvedReason = Objects.requireNonNullElse(unresolvedReason, "");
        }

        private String canonicalKey() {
            return encode(from.canonicalKey(), type.name(), to.canonicalKey(), branchKind.name(),
                branchEvidence, sourceResource, unresolvedReason);
        }

        private String mechanicalIdentityKey() {
            return encode(from.canonicalKey(), type.name(), to.canonicalKey(), branchKind.name(),
                branchEvidence);
        }
    }

    public static final class Builder {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Relationship> relationships = new ArrayList<>();

        public Builder addNode(NodeType type, String identifier, String sourceResource, String rawEvidence) {
            nodes.add(new Node(new NodeRef(type, identifier), sourceResource, rawEvidence));
            return this;
        }

        public Builder addRelationship(NodeRef from, RelationshipType type, NodeRef to,
            BranchKind branchKind, String branchEvidence, String sourceResource, String unresolvedReason) {
            relationships.add(new Relationship(from, type, to, branchKind, branchEvidence,
                sourceResource, unresolvedReason));
            return this;
        }

        public WorldgenCausalEvidence build() {
            return new WorldgenCausalEvidence(nodes, relationships);
        }
    }

    private static String encode(String... values) {
        StringBuilder key = new StringBuilder();
        for (String value : values) {
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
