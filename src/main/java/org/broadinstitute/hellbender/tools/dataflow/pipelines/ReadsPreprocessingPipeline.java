package org.broadinstitute.hellbender.tools.dataflow.pipelines;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.SerializableCoder;
import com.google.cloud.dataflow.sdk.transforms.*;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.ArgumentCollection;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.OptionalIntervalArgumentCollection;
import org.broadinstitute.hellbender.engine.dataflow.DataflowCommandLineProgram;
import org.broadinstitute.hellbender.engine.dataflow.GoogleReadToReadDataflowTransform;
import org.broadinstitute.hellbender.engine.dataflow.ReadContextData;
import org.broadinstitute.hellbender.engine.dataflow.ReadsSource;
import org.broadinstitute.hellbender.engine.dataflow.transforms.composite.AddContextDataToReads;
import org.broadinstitute.hellbender.utils.GenomeLocSortedSet;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.read.Read;
import org.broadinstitute.hellbender.utils.read.ReadUtils;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

public class ReadsPreprocessingPipeline extends DataflowCommandLineProgram {

    @Argument(doc = "uri for the input bam, either a local file path or a gs:// bucket path",
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME, fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            optional = false)
    protected String bam;

    @Argument(doc = "", shortName = "R", fullName = "reference", optional = false)
    protected String referenceName;

    @Argument(doc = "", shortName = "BQSRKnownVariants", fullName = "baseRecalibrationKnownVariants", optional = true)
    protected List<String> baseRecalibrationKnownVariants;

    @ArgumentCollection
    protected IntervalArgumentCollection intervalArgumentCollection = new OptionalIntervalArgumentCollection();

    @Override
    protected void setupPipeline( Pipeline pipeline ) {
        final ReadsSource readsSource = new ReadsSource(bam, pipeline);
        final String headerString = readsSource.getHeaderString();
        final SAMFileHeader readsHeader = ReadUtils.samHeaderFromString(headerString);
        final List<SimpleInterval> intervals = intervalArgumentCollection.intervalsSpecified() ? intervalArgumentCollection.getIntervals(readsHeader.getSequenceDictionary()):
                                                                                                 getAllIntervalsForReference(readsHeader.getSequenceDictionary());

        final PCollectionView<SAMFileHeader> headerSingleton = pipeline.apply(Create.of(readsHeader)).setCoder(SerializableCoder.of(SAMFileHeader.class)).apply(View.<SAMFileHeader>asSingleton());
        final PCollection<com.google.api.services.genomics.model.Read> rawReads = readsSource.getReadPCollection(intervals);

        final PCollection<Read> initialReads = rawReads.apply(new GoogleReadToReadDataflowTransform());

        final PCollection<Read> markedReads = initialReads.apply(new MarkDuplicatesStub(headerSingleton));

        final PCollection<KV<Read, ReadContextData>> readsWithContext = markedReads.apply(new AddContextDataToReads(referenceName, baseRecalibrationKnownVariants));
        final PCollection<GATKReportStub> recalibrationReports = readsWithContext.apply(new BaseRecalibratorStub(headerSingleton));
        final PCollectionView<GATKReportStub> mergedRecalibrationReport = recalibrationReports.apply(View.<GATKReportStub>asSingleton());

        final PCollection<Read> finalReads = markedReads.apply(new ApplyBQSRStub(headerSingleton, mergedRecalibrationReport));
    }

    private List<SimpleInterval> getAllIntervalsForReference(SAMSequenceDictionary sequenceDictionary) {
        return GenomeLocSortedSet.createSetFromSequenceDictionary(sequenceDictionary)
                .stream()
                .map(SimpleInterval::new)
                .collect(Collectors.toList());
    }

    // NOTE: need a way to ensure that certain tools are guaranteed to have a header -- one option is
    // an interface with a factory method
    private static class MarkDuplicatesStub extends PTransform<PCollection<Read>, PCollection<Read>> {

        private PCollectionView<SAMFileHeader> header;

        public MarkDuplicatesStub( final PCollectionView<SAMFileHeader> header ) {
            this.header = header;
        }

        @Override
        public PCollection<Read> apply( PCollection<Read> input ) {
            return input.apply(ParDo.named("MarkDuplicates").
                    of(new DoFn<Read, Read>() {
                        @Override
                        public void processElement( ProcessContext c ) throws Exception {
                            c.output(c.element());
                        }
                    }).withSideInputs(header));
        }
    }

    private static class BaseRecalibratorStub extends PTransform<PCollection<KV<Read, ReadContextData>>, PCollection<GATKReportStub>> {
        private PCollectionView<SAMFileHeader> header;

        public BaseRecalibratorStub( final PCollectionView<SAMFileHeader> header ) {
            this.header = header;
        }

        @Override
        public PCollection<GATKReportStub> apply( PCollection<KV<Read, ReadContextData>> input ) {
            return input.apply(ParDo.named("BaseRecalibrator").
                    of(new DoFn<KV<Read, ReadContextData>, GATKReportStub>() {

                        @Override
                        public void processElement( ProcessContext c ) throws Exception {
                            c.output(new GATKReportStub());
                        }
                    }).withSideInputs(header));
        }
    }

    private static class ApplyBQSRStub extends PTransform<PCollection<Read>, PCollection<Read>> {
        private PCollectionView<SAMFileHeader> header;
        private PCollectionView<GATKReportStub> recalibrationReport;

        public ApplyBQSRStub( final PCollectionView<SAMFileHeader> header, final PCollectionView<GATKReportStub> recalibrationReport ) {
            this.header = header;
            this.recalibrationReport = recalibrationReport;
        }

        @Override
        public PCollection<Read> apply( PCollection<Read> input ) {
            return input.apply(ParDo.named("ApplyBQSR").
                    of(new DoFn<Read, Read>() {

                        @Override
                        public void processElement( ProcessContext c ) throws Exception {
                            c.output(c.element());
                        }
                    }).withSideInputs(header, recalibrationReport));
        }
    }

    private static class GATKReportStub implements Serializable {}
}
