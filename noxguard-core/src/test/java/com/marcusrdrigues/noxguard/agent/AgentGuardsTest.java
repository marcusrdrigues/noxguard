package com.marcusrdrigues.noxguard.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentGuardsTest {

    @Test
    @DisplayName("a proposal shows only when the answer is not a refusal")
    void proposalNeedsANonRefusal() {
        ProposalGate<String> kept = ProposalGate.create();
        assertTrue(kept.hold("rascunho"));
        assertTrue(kept.isHolding());
        assertEquals(Optional.of("rascunho"), kept.release(false));

        ProposalGate<String> dropped = ProposalGate.create();
        dropped.hold("rascunho para o RH");
        assertEquals(Optional.empty(), dropped.release(true), "the model tried, the code said no");

        assertEquals(Optional.empty(), ProposalGate.<String>create().release(false), "nothing proposed");
    }

    @Test
    @DisplayName("one proposal per answer, and one gate per answer")
    void oneProposalPerAnswer() {
        ProposalGate<String> gate = ProposalGate.create();
        assertTrue(gate.hold("primeiro"));
        assertFalse(gate.hold("segundo"));
        assertEquals(Optional.of("primeiro"), gate.release(false));
        assertFalse(gate.isHolding());
        assertThrows(IllegalStateException.class, () -> gate.release(false));
        assertThrows(IllegalStateException.class, () -> gate.hold("depois"));
    }

    @Test
    @DisplayName("after the cap, the model must answer and extra calls get no run")
    void toolBudgetForcesAnAnswer() {
        ToolBudget budget = ToolBudget.of(3);
        assertEquals(ToolChoice.AUTO, budget.nextChoice());
        assertTrue(budget.tryUse());
        assertTrue(budget.tryUse());
        assertEquals(ToolChoice.AUTO, budget.nextChoice());
        assertTrue(budget.tryUse());
        assertEquals(ToolChoice.NONE, budget.nextChoice());
        assertFalse(budget.tryUse(), "a fourth call does not run");
        assertEquals(4, budget.used());
        assertEquals(0, budget.remaining());
        assertEquals(ToolChoice.NONE, ToolBudget.of(0).nextChoice());
        assertThrows(IllegalArgumentException.class, () -> ToolBudget.of(-1));
    }

    @Test
    @DisplayName("strict schema: closed object, everything required, no keyword strict mode refuses")
    void strictSchema() {
        Map<String, Object> slug = new LinkedHashMap<>();
        slug.put("type", "string");
        slug.put("minLength", 1);
        slug.put("maxLength", 80);
        slug.put("description", "Case slug.");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", Map.of("slug", slug));
        schema.put("required", List.of("slug"));
        schema.put("additionalProperties", false);

        assertEquals(Map.of(
                        "type", "object",
                        "properties", Map.of("slug", Map.of("type", "string", "description", "Case slug.")),
                        "required", List.of("slug"),
                        "additionalProperties", false),
                StrictSchema.of(schema));
        assertEquals(Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false),
                StrictSchema.of(Map.of("type", "object")));
    }

    @Test
    @DisplayName("strict schema: optional fields become nullable, nested objects are closed, order is kept")
    void strictSchemaNested() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("assunto", Map.of("type", "string", "maxLength", 80));
        properties.put("contato", Map.of("anyOf", List.of(Map.of("type", "string", "maxLength", 120), Map.of("type", "null"))));
        properties.put("nome", Map.of("type", "string"));
        properties.put("tags", Map.of("type", "array", "items", Map.of(
                "type", "object", "properties", Map.of("nome", Map.of("type", "string")), "required", List.of("nome"))));
        Map<String, Object> strict = StrictSchema.of(Map.of(
                "type", "object", "properties", properties, "required", List.of("assunto", "contato", "tags")));

        assertEquals(List.of("assunto", "contato", "nome", "tags"), strict.get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) strict.get("properties");
        assertEquals(Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null"))), props.get("contato"),
                "already nullable: kept as it is");
        assertEquals(Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null"))), props.get("nome"),
                "optional: required but nullable, so the model sends null instead of inventing a value");
        assertEquals(Map.of("type", "array", "items", Map.of(
                        "type", "object",
                        "properties", Map.of("nome", Map.of("type", "string")),
                        "required", List.of("nome"),
                        "additionalProperties", false)),
                props.get("tags"));
        assertThrows(UnsupportedOperationException.class, () -> strict.put("x", 1), "the result is unmodifiable");
        assertThrows(IllegalArgumentException.class, () -> StrictSchema.of(Map.of("type", "object", "properties", List.of())));
    }

    @Test
    @DisplayName("strict schema: a deep copy, so changing the input later changes nothing")
    void strictSchemaIsADeepCopy() {
        List<String> colors = new java.util.ArrayList<>(List.of("red", "green"));
        Map<String, Object> color = new LinkedHashMap<>();
        color.put("type", "string");
        color.put("enum", colors);
        Map<String, Object> strict = StrictSchema.of(Map.of("type", "object", "properties", Map.of("color", color), "required", List.of("color")));
        colors.add("blue");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) strict.get("properties");
        @SuppressWarnings("unchecked")
        List<Object> copied = (List<Object>) ((Map<String, Object>) props.get("color")).get("enum");
        assertEquals(List.of("red", "green"), copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.add("blue"));
    }

    @Test
    @DisplayName("strict schema: a type list with object is closed too")
    void strictSchemaTypeList() {
        Map<String, Object> strict = StrictSchema.of(Map.of("type", List.of("object", "null"), "properties", Map.of()));
        assertEquals(false, strict.get("additionalProperties"));
    }
}
