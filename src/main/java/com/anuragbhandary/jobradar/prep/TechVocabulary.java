package com.anuragbhandary.jobradar.prep;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The technologies worth noticing in a job description.
 *
 * <p>A fixed list, not extraction. Pulling "notable terms" out of prose with
 * frequency or capitalisation finds "Our", "Team" and "Engineering" - the words a
 * job advert repeats - and misses "gRPC", which appears once. The interesting
 * words in this domain are a closed set of a couple of hundred, and enumerating
 * them is both more accurate and answerable in a test.
 *
 * <p>Order is significant only for the multi-word entries: "spring boot" has to be
 * checked before "spring" or the more specific match is never reported.
 */
final class TechVocabulary {

    private TechVocabulary() {
    }

    static final List<String> TERMS = List.of(
            // Languages. "go" and "c" are here and are why matching is whole-word:
            // they are inside "django", "algorithm" and "category".
            "java", "kotlin", "scala", "python", "golang", "go", "rust", "c++", "c#",
            "typescript", "javascript", "ruby", "php", "swift", "elixir", "clojure",

            // Frameworks. Longest first within a family.
            "spring boot", "spring cloud", "spring security", "spring data", "spring",
            "quarkus", "micronaut", "vert.x", "dropwizard", "hibernate", "jpa",
            "fastapi", "django", "flask", "celery", "pydantic", "asyncio",
            "node.js", "express", "nestjs", "react", "next.js", "vue", "angular",
            "rails", "laravel", ".net", "gin", "actix",

            // Messaging and streaming.
            "kafka", "rabbitmq", "pulsar", "nats", "sqs", "sns", "kinesis",
            "flink", "spark", "beam", "airflow", "dagster", "dbt", "debezium",

            // Data stores.
            "postgresql", "postgres", "mysql", "mariadb", "sqlite", "oracle",
            "mongodb", "cassandra", "dynamodb", "redis", "memcached", "elasticsearch",
            "opensearch", "clickhouse", "snowflake", "bigquery", "redshift", "neo4j",
            "cockroachdb", "vitess", "duckdb",

            // Infrastructure.
            "docker", "kubernetes", "k8s", "helm", "terraform", "pulumi", "ansible",
            "aws", "gcp", "azure", "lambda", "ecs", "eks", "fargate", "cloudformation",
            "linux", "nginx", "envoy", "istio", "consul", "vault",

            // Interfaces and protocols.
            "rest", "graphql", "grpc", "protobuf", "websockets", "websocket",
            "openapi", "swagger", "soap", "webhooks", "oauth", "jwt", "saml",

            // Practice.
            "microservices", "monolith", "event-driven", "event sourcing", "cqrs",
            "domain-driven", "ddd", "distributed systems", "observability",
            "prometheus", "grafana", "datadog", "opentelemetry", "sentry",
            "ci/cd", "jenkins", "github actions", "gitlab ci", "argocd",
            "tdd", "junit", "pytest", "mockito", "testcontainers", "cypress",
            "agile", "scrum", "kanban",

            // AI and ML.
            "pytorch", "tensorflow", "scikit-learn", "keras", "huggingface",
            "langchain", "llamaindex", "openai", "anthropic", "gemini", "groq",
            "llm", "rag", "embeddings", "vector database", "pinecone", "weaviate",
            "mlops", "kubeflow", "sagemaker", "nlp", "computer vision");

    /** Every vocabulary term appearing in the text, in vocabulary order. */
    static Set<String> found(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        for (String term : TERMS) {
            if (containsWord(haystack, term)) {
                hits.add(term);
            }
        }
        return hits;
    }

    /**
     * Whole-word containment, with '+', '#' and a followed-by-alphanumeric '.'
     * counted as part of a word.
     *
     * <p>Otherwise "c" matches inside "c++", "go" inside "django", and "node"
     * inside "node.js" - and a trailing full stop stops "spring boot." matching at
     * the end of a sentence, which is where technologies are usually listed.
     */
    static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !isWordCharAt(haystack, at - 1);
            int end = at + needle.length();
            boolean endOk = end == haystack.length() || !isWordCharAt(haystack, end);
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordCharAt(String text, int index) {
        char c = text.charAt(index);
        if (Character.isLetterOrDigit(c) || c == '+' || c == '#') {
            return true;
        }
        return c == '.' && index + 1 < text.length()
                && Character.isLetterOrDigit(text.charAt(index + 1));
    }
}
