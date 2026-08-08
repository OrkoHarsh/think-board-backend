package com.nimbusboard.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval suite for model output validators.
 *
 * <p>The HLD cases are regression cover for the existing architecture pipeline: they must keep
 * passing exactly as before the flowchart and class diagram types were added.
 */
class DiagramEvalTest {

    @Nested
    @DisplayName("HLD (existing behaviour)")
    class Hld {

        private static final String URL_SHORTENER = """
                graph LR
                A[Client] --> B[CDN]
                B[CDN] --> C[Load Balancer]
                C[Load Balancer] --> D[API Gateway]
                D[API Gateway] --> E[URL Shortening Service]
                E[URL Shortening Service] --> F[Redis Cache]
                E[URL Shortening Service] --> G[PostgreSQL]
                E[URL Shortening Service] --> H[Analytics Service]""";

        @Test
        void acceptsDocumentedExample() {
            assertThat(OpenAiClient.isValidMermaidSyntax(URL_SHORTENER)).isTrue();
            assertThat(OpenAiClient.isValidMermaidArchitecture(URL_SHORTENER)).isTrue();
            assertThat(OpenAiClient.isValidForType(URL_SHORTENER, DiagramType.HLD)).isTrue();
        }

        @Test
        void rejectsArrowLabels() {
            assertThat(OpenAiClient.isValidMermaidSyntax("graph LR\nA[Client] -->|http| B[API]")).isFalse();
        }

        @Test
        void rejectsWrongHeader() {
            assertThat(OpenAiClient.isValidMermaidSyntax("flowchart TD\nA[Client] --> B[API]")).isFalse();
        }

        @Test
        void rejectsCacheToDatabaseChain() {
            String chained = """
                    graph LR
                    A[Client] --> B[Service]
                    B[Service] --> C[Redis Cache]
                    C[Redis Cache] --> D[PostgreSQL]""";
            assertThat(OpenAiClient.isValidMermaidArchitecture(chained)).isFalse();
        }

        @Test
        void rejectsEdgeIntoClient() {
            String backEdge = """
                    graph LR
                    A[Client] --> B[Service]
                    B[Service] --> C[Worker]
                    C[Worker] --> A[Client]""";
            assertThat(OpenAiClient.isValidMermaidArchitecture(backEdge)).isFalse();
        }

        @Test
        void rejectsPayloadNodes() {
            String payload = """
                    graph LR
                    A[Client] --> B[Service]
                    B[Service] --> C[JWT]""";
            assertThat(OpenAiClient.isValidMermaidArchitecture(payload)).isFalse();
        }

        @Test
        void stripsMarkdownFences() {
            assertThat(OpenAiClient.stripMarkdownFences("```mermaid\ngraph LR\nA[C] --> B[D]\n```"))
                    .isEqualTo("graph LR\nA[C] --> B[D]");
        }
    }

    @Nested
    @DisplayName("Flowchart")
    class Flowchart {

        private static final String LOGIN_FLOW = """
                flowchart TD
                A([Start]) --> B[Open login page]
                B --> C[Enter credentials]
                C --> D{Credentials valid?}
                D -->|No| E[Show error]
                E --> C
                D -->|Yes| F[Create session]
                F --> G([End])""";

        @Test
        void acceptsDocumentedExample() {
            assertThat(OpenAiClient.isValidFlowchart(LOGIN_FLOW)).isTrue();
            assertThat(OpenAiClient.isValidForType(LOGIN_FLOW, DiagramType.FLOWCHART)).isTrue();
        }

        @Test
        void acceptsLabelledBranches() {
            String branched = """
                    flowchart TD
                    A([Start]) --> B{Approved?}
                    B -->|Yes| C[Ship order]
                    B -->|No| D[Notify customer]
                    C --> E([End])
                    D --> E([End])""";
            assertThat(OpenAiClient.isValidFlowchart(branched)).isTrue();
        }

        @Test
        void rejectsMissingHeader() {
            assertThat(OpenAiClient.isValidFlowchart("A([Start]) --> B[Step]")).isFalse();
        }

        @Test
        void rejectsCommentaryLines() {
            String chatty = """
                    flowchart TD
                    Here is your diagram:
                    A([Start]) --> B[Step]""";
            assertThat(OpenAiClient.isValidFlowchart(chatty)).isFalse();
        }

        @Test
        @DisplayName("rejects multiple entry points so the layout has one root")
        void rejectsMultipleRoots() {
            String twoRoots = """
                    flowchart TD
                    A([Start]) --> C[Step]
                    B([Other start]) --> D[Step two]""";
            assertThat(OpenAiClient.isValidFlowchart(twoRoots)).isFalse();
        }

        @Test
        void rejectsSelfLoop() {
            String selfLoop = """
                    flowchart TD
                    A([Start]) --> B[Step]
                    B --> B[Step]""";
            assertThat(OpenAiClient.isValidFlowchart(selfLoop)).isFalse();
        }

        @Test
        void rejectsOversizedGraph() {
            StringBuilder sb = new StringBuilder("flowchart TD\n");
            for (int i = 0; i < 30; i++) {
                sb.append("N").append(i).append("[Step ").append(i).append("] --> N")
                        .append(i + 1).append("[Step ").append(i + 1).append("]\n");
            }
            assertThat(OpenAiClient.isValidFlowchart(sb.toString())).isFalse();
        }

        @Test
        @DisplayName("HLD output is not accepted as a flowchart and vice versa")
        void typesAreDistinct() {
            assertThat(OpenAiClient.isValidForType(LOGIN_FLOW, DiagramType.HLD)).isFalse();
        }
    }

    @Nested
    @DisplayName("Class diagram")
    class ClassDiagram {

        private static final String ORDERS = """
                classDiagram
                class Customer {
                +UUID id
                +String name
                +placeOrder() Order
                }
                class Order {
                +UUID id
                +String status
                +total() Money
                }
                class OrderLine {
                +int quantity
                +Money unitPrice
                }
                Customer --> Order
                Order *-- OrderLine""";

        @Test
        void acceptsDocumentedExample() {
            assertThat(OpenAiClient.isValidClassDiagram(ORDERS)).isTrue();
            assertThat(OpenAiClient.isValidForType(ORDERS, DiagramType.CLASS)).isTrue();
        }

        @Test
        void acceptsInheritanceAndAggregation() {
            String inheritance = """
                    classDiagram
                    class Animal {
                    +String name
                    }
                    class Dog {
                    +bark() void
                    }
                    class Owner {
                    +String name
                    }
                    Animal <|-- Dog
                    Owner o-- Dog""";
            assertThat(OpenAiClient.isValidClassDiagram(inheritance)).isTrue();
        }

        @Test
        void rejectsMissingHeader() {
            assertThat(OpenAiClient.isValidClassDiagram("class User {\n+UUID id\n}")).isFalse();
        }

        @Test
        void rejectsUnbalancedBlock() {
            String unbalanced = """
                    classDiagram
                    class User {
                    +UUID id""";
            assertThat(OpenAiClient.isValidClassDiagram(unbalanced)).isFalse();
        }

        @Test
        void rejectsEmptyClass() {
            String empty = """
                    classDiagram
                    class User {
                    }""";
            assertThat(OpenAiClient.isValidClassDiagram(empty)).isFalse();
        }

        @Test
        @DisplayName("members without a visibility prefix are rejected")
        void rejectsUnprefixedMembers() {
            String unprefixed = """
                    classDiagram
                    class User {
                    UUID id
                    }""";
            assertThat(OpenAiClient.isValidClassDiagram(unprefixed)).isFalse();
        }

        @Test
        @DisplayName("relationships must reference declared classes")
        void rejectsUnknownRelationTarget() {
            String dangling = """
                    classDiagram
                    class User {
                    +UUID id
                    }
                    User --> Ghost""";
            assertThat(OpenAiClient.isValidClassDiagram(dangling)).isFalse();
        }

        @Test
        void rejectsCommentary() {
            String chatty = """
                    classDiagram
                    Sure! Here is the diagram.
                    class User {
                    +UUID id
                    }""";
            assertThat(OpenAiClient.isValidClassDiagram(chatty)).isFalse();
        }
    }

    @Nested
    @DisplayName("Type resolution")
    class TypeResolution {

        @Test
        void defaultsToHldSoExistingBehaviourIsUnchanged() {
            assertThat(DiagramType.resolve(null, "scalable HLD for URL shortener")).isEqualTo(DiagramType.HLD);
            assertThat(DiagramType.resolve(null, "auth login architecture")).isEqualTo(DiagramType.HLD);
            assertThat(DiagramType.resolve(null, "design a chat application")).isEqualTo(DiagramType.HLD);
            assertThat(DiagramType.resolve("", null)).isEqualTo(DiagramType.HLD);
        }

        @Test
        void explicitSelectionWins() {
            assertThat(DiagramType.resolve("FLOWCHART", "url shortener")).isEqualTo(DiagramType.FLOWCHART);
            assertThat(DiagramType.resolve("class", "url shortener")).isEqualTo(DiagramType.CLASS);
            assertThat(DiagramType.resolve("hld", "class diagram for orders")).isEqualTo(DiagramType.HLD);
        }

        @Test
        void infersFromUnmistakableWording() {
            assertThat(DiagramType.resolve(null, "class diagram for an order system")).isEqualTo(DiagramType.CLASS);
            assertThat(DiagramType.resolve(null, "UML class diagram for a library")).isEqualTo(DiagramType.CLASS);
            assertThat(DiagramType.resolve(null, "user onboarding flowchart")).isEqualTo(DiagramType.FLOWCHART);
            assertThat(DiagramType.resolve(null, "checkout flow chart")).isEqualTo(DiagramType.FLOWCHART);
        }

        @Test
        void unknownValueFallsBackToInference() {
            assertThat(DiagramType.resolve("nonsense", "user onboarding flowchart")).isEqualTo(DiagramType.FLOWCHART);
            assertThat(DiagramType.resolve("nonsense", "url shortener")).isEqualTo(DiagramType.HLD);
        }

        @Test
        void acceptsCommonAliases() {
            assertThat(DiagramType.resolve("uml", "x")).isEqualTo(DiagramType.CLASS);
            assertThat(DiagramType.resolve("flow", "x")).isEqualTo(DiagramType.FLOWCHART);
            assertThat(DiagramType.resolve("architecture", "x")).isEqualTo(DiagramType.HLD);
        }
    }
}
