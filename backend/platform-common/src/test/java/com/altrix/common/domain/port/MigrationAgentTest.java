package com.altrix.common.domain.port;

import com.altrix.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationAgentTest {

    // -------------------------------------------------------------------------
    // Minimal in-test implementations
    // -------------------------------------------------------------------------

    private static MigrationAgent<String, Integer> lengthAgent(String name, int order) {
        return new MigrationAgent<>() {
            @Override public String getName()          { return name; }
            @Override public int    getOrder()         { return order; }
            @Override public Integer execute(String s) { return s.length(); }
        };
    }

    private static MigrationAgent<String, String> upperAgent(String name, int order) {
        return new MigrationAgent<>() {
            @Override public String getName()           { return name; }
            @Override public int    getOrder()          { return order; }
            @Override public String execute(String s)   { return s.toUpperCase(); }
        };
    }

    private static MigrationAgent<String, String> failingAgent(String name, int order) {
        return new MigrationAgent<>() {
            @Override public String getName()           { return name; }
            @Override public int    getOrder()          { return order; }
            @Override public String execute(String s)   {
                throw new AgentFailureException(name, "deliberate test failure");
            }
        };
    }

    // -------------------------------------------------------------------------
    // Contract: getName / getOrder
    // -------------------------------------------------------------------------

    @Test
    void getName_returns_the_configured_name() {
        assertThat(lengthAgent("analyzer", 1).getName()).isEqualTo("analyzer");
    }

    @Test
    void getOrder_returns_the_configured_order() {
        assertThat(lengthAgent("migrator", 3).getOrder()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Contract: execute with typed I/O
    // -------------------------------------------------------------------------

    @Test
    void execute_transforms_string_input_to_integer_output() {
        MigrationAgent<String, Integer> agent = lengthAgent("counter", 1);
        assertThat(agent.execute("hello")).isEqualTo(5);
        assertThat(agent.execute("")).isEqualTo(0);
        assertThat(agent.execute("kafka")).isEqualTo(5);
    }

    @Test
    void execute_transforms_string_input_to_string_output() {
        MigrationAgent<String, String> agent = upperAgent("upper", 2);
        assertThat(agent.execute("pubsub")).isEqualTo("PUBSUB");
    }

    @Test
    void execute_is_called_with_exact_input_passed_in() {
        AtomicBoolean called = new AtomicBoolean(false);
        MigrationAgent<String, String> spy = new MigrationAgent<>() {
            @Override public String getName()         { return "spy"; }
            @Override public int    getOrder()        { return 1; }
            @Override public String execute(String s) {
                called.set(true);
                assertThat(s).isEqualTo("exact-input");
                return s;
            }
        };
        spy.execute("exact-input");
        assertThat(called).isTrue();
    }

    // -------------------------------------------------------------------------
    // Contract: exception propagation
    // -------------------------------------------------------------------------

    @Test
    void execute_propagates_AgentFailureException_to_caller() {
        MigrationAgent<String, String> agent = failingAgent("broken", 1);
        assertThatThrownBy(() -> agent.execute("anything"))
                .isInstanceOf(AgentFailureException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void execute_propagates_any_runtime_exception() {
        MigrationAgent<String, String> agent = new MigrationAgent<>() {
            @Override public String getName()         { return "unstable"; }
            @Override public int    getOrder()        { return 1; }
            @Override public String execute(String s) { throw new IllegalStateException("provider unavailable"); }
        };
        assertThatThrownBy(() -> agent.execute("input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
    }

    // -------------------------------------------------------------------------
    // Contract: ordering
    // -------------------------------------------------------------------------

    @Test
    void agents_sorted_by_order_run_in_ascending_sequence() {
        MigrationAgent<?, ?> a1 = lengthAgent("first",  1);
        MigrationAgent<?, ?> a3 = upperAgent ("third",  3);
        MigrationAgent<?, ?> a2 = upperAgent ("second", 2);

        List<MigrationAgent<?, ?>> sorted = List.of(a3, a1, a2).stream()
                .sorted(Comparator.comparingInt(MigrationAgent::getOrder))
                .toList();

        assertThat(sorted).containsExactly(a1, a2, a3);
    }

    @Test
    void order_gap_between_agents_is_preserved_after_sort() {
        // Order gaps (1, 3, 5) are intentional — space for future agents
        MigrationAgent<?, ?> a5 = upperAgent("five",  5);
        MigrationAgent<?, ?> a1 = upperAgent("one",   1);
        MigrationAgent<?, ?> a3 = upperAgent("three", 3);

        List<Integer> orders = List.of(a5, a1, a3).stream()
                .sorted(Comparator.comparingInt(MigrationAgent::getOrder))
                .map(MigrationAgent::getOrder)
                .toList();

        assertThat(orders).containsExactly(1, 3, 5);
    }

    // -------------------------------------------------------------------------
    // Type-system guarantee: same interface, different type parameters
    // -------------------------------------------------------------------------

    @Test
    void interface_is_polymorphic_across_different_type_parameters() {
        MigrationAgent<String, Integer> intAgent    = lengthAgent("len",   1);
        MigrationAgent<String, String>  stringAgent = upperAgent ("upper", 2);

        // Both are MigrationAgent — verifiable at runtime via raw type
        assertThat(intAgent).isInstanceOf(MigrationAgent.class);
        assertThat(stringAgent).isInstanceOf(MigrationAgent.class);
    }

    @Test
    void two_agents_with_same_order_can_coexist_in_a_list() {
        MigrationAgent<String, Integer> a = lengthAgent("a", 1);
        MigrationAgent<String, Integer> b = lengthAgent("b", 1);

        List<MigrationAgent<?, ?>> agents = List.of(a, b);
        assertThat(agents).hasSize(2);
        assertThat(agents).allSatisfy(agent -> assertThat(agent.getOrder()).isEqualTo(1));
    }
}
