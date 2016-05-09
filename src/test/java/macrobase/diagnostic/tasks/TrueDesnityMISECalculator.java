package macrobase.diagnostic.tasks;

import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import macrobase.analysis.pipeline.BasePipeline;
import macrobase.analysis.pipeline.operator.MBStream;
import macrobase.analysis.result.AnalysisResult;
import macrobase.analysis.stats.BatchTrainScore;
import macrobase.analysis.stats.distribution.Mixture;
import macrobase.analysis.stats.distribution.MultivariateDistribution;
import macrobase.analysis.stats.distribution.MultivariateNormal;
import macrobase.analysis.summary.itemset.result.ItemsetResult;
import macrobase.analysis.transform.BatchScoreFeatureTransform;
import macrobase.analysis.transform.FeatureTransform;
import macrobase.conf.MacroBaseConf;
import macrobase.conf.MacroBaseDefaults;
import macrobase.datamodel.Datum;
import macrobase.ingest.DataIngester;
import macrobase.util.AlgebraUtils;
import macrobase.util.DiagnosticsUtils;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.BlockRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TrueDesnityMISECalculator extends ConfiguredCommand<MacroBaseConf> {
    private static final Logger log = LoggerFactory.getLogger(TrueDesnityMISECalculator.class);

    public TrueDesnityMISECalculator() {
        super("mise", "Dump true density related statistics.");
    }

    @Override
    protected void run(Bootstrap<MacroBaseConf> bootstrap, Namespace namespace, MacroBaseConf macroBaseConf) throws Exception {
        AMSETask task = new AMSETask();
        task.initialize(macroBaseConf);
        task.run();
    }

    private class AMSETask extends BasePipeline {
        @Override
        public List<AnalysisResult> run() throws Exception {
            long startMs = System.currentTimeMillis();
            DataIngester ingester = conf.constructIngester();

            List<Datum> data = ingester.getStream().drain();
            long loadEndMs = System.currentTimeMillis();

            BatchScoreFeatureTransform batchTransform = new BatchScoreFeatureTransform(conf, conf.getTransformType());

            List<MultivariateDistribution> listDist = new ArrayList<>(3);
            double[] weights = {2. / 7, 3. / 7, 2. / 7};
            double[][] distData = {
                    {1.5, 2}, {0.5, 0.4, 0.4, 0.5}, {2000},
                    {2, 0}, {0.3, 0, 0, 0.6}, {3000},
                    {4.5, 1}, {0.9, 0.2, 0.2, 0.3}, {2000}};
            for (int i = 0; i < distData.length; i += 3) {
                RealVector mean = new ArrayRealVector(distData[i + 0]);
                double[][] covArray = new double[2][2];
                covArray[0] = Arrays.copyOfRange(distData[i + 1], 0, 2);
                covArray[1] = Arrays.copyOfRange(distData[i + 1], 2, 4);
                RealMatrix cov = new BlockRealMatrix(covArray);
                listDist.add(new MultivariateNormal(mean, cov));
            }

            FeatureTransform amse = new TrueScoreExpDifferenceTransform(conf, batchTransform, new Mixture(listDist, weights));
            amse.consume(data);

            final long endMs = System.currentTimeMillis();
            final long loadMs = loadEndMs - startMs;
            final long totalMs = endMs - loadEndMs;

            return Arrays.asList(new AnalysisResult(0,
                    0,
                    loadMs,
                    totalMs,
                    0,
                    new ArrayList<ItemsetResult>()));
        }
    }

    private class TrueScoreExpDifferenceTransform implements FeatureTransform{
        private final MBStream<Datum> output = new MBStream<>();
        private BatchScoreFeatureTransform underlyingTransform;
        private MultivariateDistribution trueDistribution;
        private BatchTrainScore underlyingBatchTrainScore;
        private Integer pointsPerDim;

        public TrueScoreExpDifferenceTransform(MacroBaseConf conf, BatchScoreFeatureTransform underlyingTransform, MultivariateDistribution trueDistribution) {
            this.underlyingTransform = underlyingTransform;
            this.trueDistribution = trueDistribution;
            underlyingBatchTrainScore = underlyingTransform.getBatchTrainScore();
            pointsPerDim = conf.getInt(MacroBaseConf.NUM_SCORE_GRID_POINTS_PER_DIMENSION, MacroBaseDefaults.NUM_SCORE_GRID_POINTS_PER_DIMENSION);
        }

        @Override
        public void initialize() throws Exception {

        }

        @Override
        public void consume(List<Datum> records) throws Exception {
            List<Datum> initalRecords = records;
            underlyingTransform.consume(records);

            List<Datum> transferredRecords = underlyingTransform.getStream().drain();
            output.add(transferredRecords);

            double[][] boundaries = AlgebraUtils.getBoundingBox(initalRecords);
            List<Datum> grid = DiagnosticsUtils.createGridFixedSize(boundaries, pointsPerDim);

            double squaredSum = 0;

            for (Datum d : grid) {
                squaredSum += Math.pow(Math.exp(underlyingBatchTrainScore.score(d)) - trueDistribution.density(d.getMetrics()), 2);
            }
            log.debug("squaredSum: {}", squaredSum);

            double areaPerPoint = 1;
            for (double[] minMax : boundaries) {
                areaPerPoint *= (minMax[1] - minMax[0]) / pointsPerDim;
            }

            log.debug("approximated Integral: {}", squaredSum * areaPerPoint);
        }

        @Override
        public void shutdown() throws Exception {

        }

        @Override
        public MBStream<Datum> getStream() throws Exception {
            return output;
        }
    }
}
