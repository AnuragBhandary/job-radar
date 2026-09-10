package com.anuragbhandary.jobradar.knowledge.experience;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What is near what, in this corner of software.
 *
 * <p>A fixed, hand-written map, for the same reason
 * {@link com.anuragbhandary.jobradar.prep.TechVocabulary} is a fixed list rather
 * than an extraction: the relationships that matter here are a couple of hundred,
 * they are stable, and enumerating them is both more accurate than inferring them
 * and answerable in a test. Asking a model "what is Kubernetes related to?" would
 * produce a different graph every run and no way to say whether it was right.
 *
 * <p>It is also the safety property. The adjacency decides which of his
 * technologies a drafted answer is allowed to name; anything the graph does not
 * connect cannot be offered as related evidence, whatever a model would like to
 * say. Widening the graph is a deliberate edit to this file.
 *
 * <h2>Two kinds of edge</h2>
 * {@link #neighbours} are technologies a person who knows one is likely to know
 * something about the other - Kubernetes and Docker, Kafka and RabbitMQ. Those
 * produce {@link ExperienceLevel#ADJACENT}. {@link #concepts} are the ideas a
 * technology implements - orchestration, containerisation, infrastructure as
 * code - and matching only on those produces {@link ExperienceLevel#CONCEPTUAL},
 * which is a weaker claim and reads as one.
 */
public final class SkillGraph {

    private SkillGraph() {
    }

    /** Technology to the technologies beside it. Symmetric by construction below. */
    private static final Map<String, List<String>> NEIGHBOURS = new LinkedHashMap<>();

    /** Technology to the ideas it implements. */
    private static final Map<String, List<String>> CONCEPTS = new LinkedHashMap<>();

    /**
     * The engineering foundations, for when nothing else connects.
     *
     * <p>Deliberately short. A list of twenty "capabilities" would let anything
     * be positioned as transferable to anything, which is the same as having no
     * standard at all.
     */
    private static final List<String> FOUNDATIONS = List.of(
            "backend engineering", "distributed systems", "asynchronous processing",
            "api design", "relational databases", "testing");

    private static void edge(String technology, String... related) {
        NEIGHBOURS.computeIfAbsent(key(technology), k -> new java.util.ArrayList<>())
                .addAll(List.of(related));
        // Both ways. A one-directional graph means Kubernetes finds Docker and
        // Docker does not find Kubernetes, and which of the two a board asks
        // about is not something this file gets to choose.
        for (String other : related) {
            NEIGHBOURS.computeIfAbsent(key(other), k -> new java.util.ArrayList<>())
                    .add(technology);
        }
    }

    private static void implementsIdea(String technology, String... ideas) {
        CONCEPTS.put(key(technology), List.of(ideas));
    }

    static {
        // ---- containers and orchestration -------------------------------
        edge("kubernetes", "docker", "helm", "ecs", "eks", "fargate", "istio", "envoy");
        edge("docker", "linux", "ci/cd", "aws");
        // "container orchestration" is written out because that is the phrase
        // boards use when they ask the question without naming a product, and a
        // graph keyed only on "orchestration" did not match it.
        implementsIdea("kubernetes", "container orchestration", "containerisation",
                "orchestration", "deployment", "distributed systems", "infrastructure");
        implementsIdea("docker", "containerisation", "container orchestration",
                "deployment", "reproducible builds");
        implementsIdea("helm", "container orchestration", "orchestration", "deployment");

        // ---- infrastructure as code and cloud ---------------------------
        edge("terraform", "pulumi", "cloudformation", "ansible", "aws", "gcp", "azure");
        edge("aws", "gcp", "azure", "lambda", "ecs", "s3", "ec2");
        implementsIdea("terraform", "infrastructure", "deployment", "cloud provisioning");
        implementsIdea("ansible", "infrastructure", "deployment", "configuration management");
        implementsIdea("aws", "cloud", "infrastructure", "deployment");

        // ---- messaging and streaming ------------------------------------
        edge("kafka", "rabbitmq", "pulsar", "nats", "sqs", "sns", "kinesis", "redis");
        edge("flink", "spark", "beam", "kafka");
        edge("airflow", "dagster", "celery");
        implementsIdea("kafka", "event-driven", "streaming", "distributed systems",
                "asynchronous processing", "message queues");
        implementsIdea("rabbitmq", "message queues", "asynchronous processing");
        implementsIdea("flink", "streaming", "distributed systems");
        implementsIdea("airflow", "workflow orchestration", "job scheduling",
                "asynchronous processing");
        implementsIdea("celery", "job scheduling", "asynchronous processing");

        // ---- data stores -------------------------------------------------
        edge("postgresql", "postgres", "mysql", "mariadb", "sqlite", "oracle",
                "cockroachdb");
        edge("mongodb", "cassandra", "dynamodb", "postgresql");
        edge("redis", "memcached", "dynamodb");
        edge("elasticsearch", "opensearch", "clickhouse");
        implementsIdea("postgresql", "relational databases", "sql", "data modelling");
        implementsIdea("mongodb", "document stores", "data modelling");
        implementsIdea("cassandra", "distributed systems", "data modelling");
        implementsIdea("redis", "caching", "in-memory stores");
        implementsIdea("elasticsearch", "search", "indexing");

        // ---- jvm and backend frameworks ----------------------------------
        edge("java", "kotlin", "scala", "spring boot", "spring", "hibernate", "jpa",
                "maven", "gradle");
        edge("spring boot", "spring", "spring data", "spring security", "quarkus",
                "micronaut", "dropwizard");
        edge("hibernate", "jpa", "spring data");
        implementsIdea("spring boot", "backend engineering", "dependency injection",
                "api design");
        implementsIdea("quarkus", "backend engineering", "api design");
        implementsIdea("hibernate", "relational databases", "object mapping");

        // ---- python -------------------------------------------------------
        edge("python", "fastapi", "flask", "django", "celery", "pydantic", "asyncio");
        edge("fastapi", "flask", "django", "pydantic", "asyncio");
        implementsIdea("fastapi", "api design", "backend engineering",
                "asynchronous processing");
        implementsIdea("django", "backend engineering", "api design", "orm");

        // ---- front end ----------------------------------------------------
        edge("react", "next.js", "vue", "angular", "typescript", "javascript");
        edge("typescript", "javascript", "node.js");
        implementsIdea("react", "frontend engineering", "component ui", "state management");
        implementsIdea("vue", "frontend engineering", "component ui");
        implementsIdea("angular", "frontend engineering", "component ui");

        // ---- interfaces ----------------------------------------------------
        edge("grpc", "rest", "protobuf", "graphql", "openapi");
        edge("websockets", "websocket", "rest", "grpc");
        implementsIdea("graphql", "api design", "schema design");
        implementsIdea("grpc", "api design", "rpc", "protocol design");
        implementsIdea("websockets", "real-time systems", "api design",
                "asynchronous processing");

        // ---- observability and delivery -------------------------------------
        edge("prometheus", "grafana", "datadog", "opentelemetry", "sentry");
        edge("jenkins", "github actions", "gitlab ci", "argocd", "ci/cd");
        implementsIdea("prometheus", "observability", "monitoring");
        implementsIdea("grafana", "observability", "monitoring");
        implementsIdea("opentelemetry", "observability", "tracing");
        implementsIdea("jenkins", "ci/cd", "deployment", "automation");
        implementsIdea("github actions", "ci/cd", "deployment", "automation");

        // ---- testing ---------------------------------------------------------
        edge("junit", "mockito", "testcontainers", "pytest", "tdd");
        implementsIdea("testcontainers", "testing", "integration testing");
        implementsIdea("junit", "testing", "unit testing");
        implementsIdea("pytest", "testing", "unit testing");

        // ---- ai ----------------------------------------------------------------
        edge("pytorch", "tensorflow", "scikit-learn", "keras", "huggingface");
        edge("langchain", "llamaindex", "openai", "anthropic", "gemini", "groq", "llm");
        edge("pinecone", "weaviate", "vector database", "embeddings");
        implementsIdea("langchain", "llm", "prompt engineering", "ai services");
        implementsIdea("pytorch", "machine learning", "model training");
        implementsIdea("vector database", "embeddings", "search", "retrieval");
        implementsIdea("sagemaker", "machine learning", "cloud", "model training");
        implementsIdea("kubeflow", "machine learning", "orchestration", "deployment");
    }

    /** Technologies beside this one. Empty for anything the graph does not know. */
    public static Set<String> neighbours(String technology) {
        List<String> found = NEIGHBOURS.get(key(technology));
        return found == null ? Set.of() : new LinkedHashSet<>(found);
    }

    /** The ideas this technology implements. */
    public static Set<String> concepts(String technology) {
        List<String> found = CONCEPTS.get(key(technology));
        return found == null ? Set.of() : new LinkedHashSet<>(found);
    }

    /** The broad engineering ground used when nothing more specific connects. */
    public static List<String> foundations() {
        return FOUNDATIONS;
    }

    /** True when the graph has anything at all to say about a term. */
    public static boolean knows(String technology) {
        String key = key(technology);
        return NEIGHBOURS.containsKey(key) || CONCEPTS.containsKey(key);
    }

    /** Every technology the graph names, for a test that walks the whole thing. */
    public static Set<String> everyTechnology() {
        Set<String> all = new LinkedHashSet<>(NEIGHBOURS.keySet());
        all.addAll(CONCEPTS.keySet());
        return all;
    }

    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
