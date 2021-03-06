package com.github.bsideup.liiklus.records.tests;

import com.github.bsideup.liiklus.records.RecordStorageTestSupport;
import com.github.bsideup.liiklus.records.RecordsStorage;
import lombok.val;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public interface SubscribeTest extends RecordStorageTestSupport {

    @Test
    default void testSubscribeWithEarliest() throws Exception {
        val numRecords = 5;
        val key = UUID.randomUUID().toString().getBytes();

        val offsetInfos = publishMany(key, numRecords);

        val partition = offsetInfos.get(0).getPartition();

        val disposeAll = DirectProcessor.<Boolean>create();

        try {
            val recordsSoFar = new ArrayList<RecordsStorage.Record>();

            subscribeToPartition(partition, "earliest")
                    .flatMap(RecordsStorage.PartitionSource::getPublisher)
                    .takeUntilOther(disposeAll)
                    .subscribe(recordsSoFar::add);

            await.untilAsserted(() -> {
                assertThat(recordsSoFar).hasSize(numRecords);
            });

            publish(key, UUID.randomUUID().toString().getBytes());

            await.untilAsserted(() -> {
                assertThat(recordsSoFar).hasSize(numRecords + 1);
            });
        } finally {
            disposeAll.onNext(true);
        }
    }

    @Test
    default void testSubscribeWithLatest() throws Exception {
        val key = UUID.randomUUID().toString().getBytes();

        val offsetInfos = publishMany(key, 5);

        val partition = offsetInfos.get(0).getPartition();

        val disposeAll = DirectProcessor.<Boolean>create();

        try {
            val recordsSoFar = new ArrayList<RecordsStorage.Record>();
            val assigned = new AtomicBoolean(false);

            subscribeToPartition(partition, "latest")
                    .doOnNext(__ -> assigned.set(true))
                    .flatMap(RecordsStorage.PartitionSource::getPublisher)
                    .takeUntilOther(disposeAll)
                    .subscribe(recordsSoFar::add);

            await.untilTrue(assigned);

            val envelope = createEnvelope(key);
            val offsetInfo = publish(envelope);

            await.untilAsserted(() -> {
                assertThat(recordsSoFar)
                        .hasSize(1)
                        .allSatisfy(it -> {
                            assertThat(it.getEnvelope()).as("envelope").isEqualTo(envelope);
                            assertThat(it.getPartition()).as("partition").isEqualTo(offsetInfo.getPartition());
                            assertThat(it.getOffset()).as("offset").isEqualTo(offsetInfo.getOffset());
                        });
            });
        } finally {
            disposeAll.onNext(true);
        }
    }

    @Test
    default void testSubscribeSorting() {
        val numRecords = 5;

        val offsetInfos = publishMany("key".getBytes(), numRecords);
        val partition = offsetInfos.get(0).getPartition();

        val records = subscribeToPartition(partition, "earliest")
                .flatMap(RecordsStorage.PartitionSource::getPublisher)
                .take(numRecords)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(records)
                .isSortedAccordingTo(Comparator.comparingLong(RecordsStorage.Record::getOffset));
    }

    @Test
    default void testSeekTo() throws Exception {
        val offsetInfos = publishMany("key".getBytes(), 10);
        val partition = offsetInfos.get(0).getPartition();

        val position = 7L;
        val receivedRecords = subscribeToPartition(partition, "earliest")
                .delayUntil(it -> Mono.fromCompletionStage(it.seekTo(position)))
                .flatMap(RecordsStorage.PartitionSource::getPublisher)
                .take(3)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(receivedRecords).extracting(RecordsStorage.Record::getOffset).containsExactly(
                position + 0,
                position + 1,
                position + 2
        );
    }
}
