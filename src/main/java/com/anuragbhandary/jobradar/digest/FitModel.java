package com.anuragbhandary.jobradar.digest;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.IntStream;

/**
 * Fit-score weights learned from the review's own decisions.
 *
 * <p>The hand weights in {@link FitScore} were set by eye, and {@code calibrate}
 * showed on 2026-10-07 that they barely separate a pick from a skip below 60:
 * roles scoring 30-39 were picked about as often as roles scoring 40-59. Every
 * review records a decision for every posting it shows, so the decisions can set
 * the weights instead.
 *
 * <p>Logistic regression over a handful of yes/no features, deliberately small:
 * the first training set had 65 picks, and a model with more knobs than that
 * would learn the picks by heart. Picks are weighted up to balance the classes,
 * so a probability of one half means "as likely a pick as a skip", which keeps
 * the 0-100 score on roughly the scale {@code min-fit} was set on.
 *
 * <p>Saved beside the applicant's other private files, never in the repository:
 * the weights are a summary of his decisions.
 */
public final class FitModel {

    /** One name per feature, in {@link FitScore#features} order. The bias is first. */
    public static final List<String> FEATURES = List.of(
            "bias",
            "skills (share of six)",
            "entry-level title", "internship", "level II title", "senior title",
            "0 years", "1 year", "2 years", "years not stated",
            // No lane features either: which country comes first is his plan
            // (remote, Mumbai, the rest of India, then abroad), not a taste to
            // learn. Trained, they put a primary relocation country well above
            // Mumbai. FitScore adds the lane on top in his order instead.
            "senior wording", "graduate wording", "employer hidden",
            // No "data or analyst title" feature on purpose: trained on 2026-10-07 it
            // weighed -1.18, learned from the finance and ops analysts the filters
            // now drop, and he wants a good data role ranked with a software one.
            "aggregator source", "big employer's own site",
            "frontend in the description");

    private static final double L2 = 1.0;
    private static final int ITERATIONS = 3000;
    private static final double LEARNING_RATE = 0.5;

    private final double[] weights;

    FitModel(double[] weights) {
        if (weights.length != FEATURES.size()) {
            throw new IllegalArgumentException("expected " + FEATURES.size() + " weights, got " + weights.length);
        }
        this.weights = weights.clone();
    }

    /** The chance this posting is a pick, with classes balanced. */
    public double probability(double[] x) {
        double z = 0;
        for (int i = 0; i < weights.length; i++) {
            z += weights[i] * x[i];
        }
        return 1 / (1 + Math.exp(-z));
    }

    public double[] weights() {
        return weights.clone();
    }

    /**
     * Gradient descent on the class-balanced log loss with an L2 penalty on
     * everything but the bias.
     */
    public static FitModel train(List<double[]> xs, List<Boolean> picked) {
        int n = xs.size();
        int d = FEATURES.size();
        long positives = picked.stream().filter(b -> b).count();
        if (positives == 0 || positives == n) {
            throw new IllegalArgumentException("need both picks and skips to train on");
        }
        double positiveWeight = (double) n / (2 * positives);
        double negativeWeight = (double) n / (2 * (n - positives));
        double[] w = new double[d];
        double[] gradient = new double[d];
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Arrays.fill(gradient, 0);
            FitModel current = new FitModel(w);
            for (int i = 0; i < n; i++) {
                double[] x = xs.get(i);
                boolean y = picked.get(i);
                double error = (current.probability(x) - (y ? 1 : 0)) * (y ? positiveWeight : negativeWeight);
                for (int j = 0; j < d; j++) {
                    gradient[j] += error * x[j];
                }
            }
            for (int j = 0; j < d; j++) {
                double penalty = j == 0 ? 0 : L2 * w[j];
                w[j] -= LEARNING_RATE * (gradient[j] + penalty) / n;
            }
        }
        return new FitModel(w);
    }

    /**
     * How often a random pick outscores a random skip: 0.5 is a coin, 1.0 is
     * perfect. Ties count half.
     */
    public static double auc(double[] scores, boolean[] picked) {
        long pairs = 0;
        double wins = 0;
        for (int i = 0; i < scores.length; i++) {
            if (!picked[i]) {
                continue;
            }
            for (int j = 0; j < scores.length; j++) {
                if (picked[j]) {
                    continue;
                }
                pairs++;
                wins += scores[i] > scores[j] ? 1 : scores[i] == scores[j] ? 0.5 : 0;
            }
        }
        return pairs == 0 ? Double.NaN : wins / pairs;
    }

    /**
     * Out-of-sample probabilities: each fold scored by a model trained on the
     * others. The folds interleave by index, so a run of one week's picks is
     * spread across them.
     */
    public static double[] crossValidatedProbabilities(List<double[]> xs, List<Boolean> picked, int folds) {
        double[] scores = new double[xs.size()];
        for (int fold = 0; fold < folds; fold++) {
            final int f = fold;
            List<double[]> trainX = new ArrayList<>();
            List<Boolean> trainY = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) {
                if (i % folds != f) {
                    trainX.add(xs.get(i));
                    trainY.add(picked.get(i));
                }
            }
            FitModel model = train(trainX, trainY);
            IntStream.range(0, xs.size()).filter(i -> i % folds == f)
                    .forEach(i -> scores[i] = model.probability(xs.get(i)));
        }
        return scores;
    }

    /** The weights, largest effect first, for the report. */
    public List<String> describe() {
        return IntStream.range(1, weights.length).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> -Math.abs(weights[i])))
                .map(i -> "%+.2f  %s".formatted(weights[i], FEATURES.get(i)))
                .toList();
    }

    public void save(Path path, String comment) throws IOException {
        Properties props = new Properties();
        for (int i = 0; i < weights.length; i++) {
            props.setProperty("w." + i + "." + FEATURES.get(i).replaceAll("[^A-Za-z0-9]+", "-"),
                    Double.toString(weights[i]));
        }
        Files.createDirectories(path.getParent());
        try (Writer out = Files.newBufferedWriter(path)) {
            props.store(out, comment);
        }
    }

    /**
     * The saved model, or empty when there is none or it was trained on a
     * different feature list: a stale file must fall back to the hand weights,
     * not misread its numbers.
     */
    public static Optional<FitModel> load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            props.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        double[] w = new double[FEATURES.size()];
        for (int i = 0; i < w.length; i++) {
            String key = "w." + i + "." + FEATURES.get(i).replaceAll("[^A-Za-z0-9]+", "-");
            String value = props.getProperty(key);
            if (value == null) {
                return Optional.empty();
            }
            w[i] = Double.parseDouble(value);
        }
        return props.size() == w.length ? Optional.of(new FitModel(w)) : Optional.empty();
    }
}
