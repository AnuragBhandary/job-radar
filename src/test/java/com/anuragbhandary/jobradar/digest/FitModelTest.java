package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FitModelTest {

    /** Picks are the rows with feature 6 ("0 years") on, with a little noise. */
    private static void sample(List<double[]> xs, List<Boolean> ys) {
        for (int i = 0; i < 200; i++) {
            double[] x = new double[FitModel.FEATURES.size()];
            x[0] = 1;
            x[6] = i % 4 == 0 ? 1 : 0;
            x[1] = (i % 7) / 6.0;
            xs.add(x);
            ys.add(x[6] == 1 ? i % 20 != 0 : i % 25 == 0);
        }
    }

    @Test
    void learnsTheSignalAndScoresItOutOfSample() {
        List<double[]> xs = new ArrayList<>();
        List<Boolean> ys = new ArrayList<>();
        sample(xs, ys);

        FitModel model = FitModel.train(xs, ys);
        assertThat(model.weights()[6]).isGreaterThan(1.0);
        assertThat(model.describe().getFirst()).endsWith("0 years");

        double[] scores = FitModel.crossValidatedProbabilities(xs, ys, 5);
        boolean[] labels = new boolean[ys.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = ys.get(i);
        }
        assertThat(FitModel.auc(scores, labels)).isGreaterThan(0.75);
    }

    @Test
    void aucOfACoinAndOfAPerfectRanking() {
        assertThat(FitModel.auc(new double[] {1, 1, 1, 1}, new boolean[] {true, false, true, false}))
                .isEqualTo(0.5);
        assertThat(FitModel.auc(new double[] {0.9, 0.1, 0.8, 0.2}, new boolean[] {true, false, true, false}))
                .isEqualTo(1.0);
    }

    @Test
    void savesAndLoadsAndIgnoresAStaleFile(@TempDir Path dir) throws Exception {
        List<double[]> xs = new ArrayList<>();
        List<Boolean> ys = new ArrayList<>();
        sample(xs, ys);
        FitModel model = FitModel.train(xs, ys);
        Path file = dir.resolve("fit-model.properties");

        model.save(file, "test");
        assertThat(FitModel.load(file)).hasValueSatisfying(
                loaded -> assertThat(loaded.weights()).containsExactly(model.weights()));

        // Trained on another feature list: fall back to the hand weights.
        Files.writeString(file, "w.0.bias=0.1\nw.1.something-else=2\n");
        assertThat(FitModel.load(file)).isEmpty();
        assertThat(FitModel.load(dir.resolve("absent.properties"))).isEmpty();
    }
}
