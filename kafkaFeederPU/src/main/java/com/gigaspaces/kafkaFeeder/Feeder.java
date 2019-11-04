package com.gigaspaces.kafkaFeeder;

import com.gigaspaces.annotation.pojo.FifoSupport;
import com.gigaspaces.document.SpaceDocument;
import com.gigaspaces.metadata.SpacePropertyDescriptor;
import com.gigaspaces.metadata.SpaceTypeDescriptor;
import com.gigaspaces.metadata.SpaceTypeDescriptorBuilder;
import com.gigaspaces.metadata.StorageType;
import com.gigaspaces.metadata.index.SpaceIndex;
import com.gigaspaces.query.extension.metadata.TypeQueryExtensions;
import org.apache.commons.io.FileUtils;
import com.gigaspaces.kafkaFeeder.model.FlightDelay;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongSerializer;

import org.openspaces.core.GigaSpace;
import org.openspaces.core.context.GigaSpaceContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A feeder bean starts a scheduled task that writes a new objects to 
 * Kafka (in an unprocessed state).
 * <p/>
 * <p/>
 * The space is injected into this bean using OpenSpaces support for @GigaSpaceContext
 * annotation.
 * <p/>
 * <p/>
 * The scheduling uses the java.util.concurrent Scheduled Executor Service. It
 * is started and stopped based on Spring lifecycle events.
 */
@Slf4j
public class Feeder implements InitializingBean, DisposableBean {

    private final Integer DEFAULT_FILE_OFFSET = 1;

    private ScheduledExecutorService executorService;

    private ScheduledFuture<?> sf;

    private long defaultDelay = 1000;


    private FeederTask feederTask;

    @Value("${kafka.bootstrapServer}")
    public String bootstrapServer;

    @Bean
    public ProducerFactory<Long, FlightDelay> producerFactory() {
        log.info("Kafka producer properties: " + producerConfigs().keySet().stream()
                .map(key -> key + "=" + producerConfigs().get(key))
                .collect(Collectors.joining(", ", "{", "}")));

        return new DefaultKafkaProducerFactory<>(producerConfigs());
    }

    @Bean
    public Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return props;
    }

    @Bean
    public KafkaTemplate<Long, FlightDelay> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }


    @GigaSpaceContext
    private GigaSpace gigaSpace;

    public void setDefaultDelay(long defaultDelay) {
        this.defaultDelay = defaultDelay;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        bootstrapServer = System.getProperty("com.gs.kafka_url");
        log.info("--- STARTING FEEDER WITH CYCLE [" + defaultDelay + "]");
        executorService = Executors.newScheduledThreadPool(1);
        feederTask = new FeederTask();
        feederTask.init();
        sf = executorService.scheduleAtFixedRate(feederTask , defaultDelay, defaultDelay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() throws Exception {
        log.info("Destroy called");
        sf.cancel(false);
        sf = null;
        executorService.shutdown();
    }

    public class FeederTask implements Runnable {
        private String kafkaTopic = "flights";

        private Integer linesToSend = 10;

        private String delaysFilePath = "/data.csv";

        private String delaysURL = "https://insightedge-gettingstarted.s3.amazonaws.com/data.csv";

        private Boolean isRepeatable = true;

        private KafkaTemplate template;
        // Skip header firstly
        private Integer fileOffset = DEFAULT_FILE_OFFSET;

        private Long recordId = 0L;

        public void init(){
            try {
                FileUtils.copyURLToFile(new URL(delaysURL), new File(delaysFilePath));
                template = kafkaTemplate();
            } catch (Exception e) {
                log.error("CopyURLtoFile failed with " + delaysURL + " and " + delaysFilePath);
                e.printStackTrace();
            }

        }

        public void run() {
            try {
                List<FlightDelay> recordsToSend = Files.lines(Paths.get(delaysFilePath))
                        .skip(fileOffset)
                        .limit(linesToSend)
                        .map(FlightDelay::fromCsvStr)
                        .collect(Collectors.toList());
                if (recordsToSend.size() > 0) {
                    fileOffset += linesToSend;
                    recordsToSend.stream().map(FlightDelay::toString).forEach(log::debug);

                    recordsToSend.forEach(flightDelay -> template.send(kafkaTopic, recordId++, flightDelay));
                } else {
                    if (isRepeatable) {
                        log.info("Application walked over all available records. Let's do it again.");
                        fileOffset = DEFAULT_FILE_OFFSET;
                    }
                }
            } catch (IOException e) {
                log.error("Error during read file: " + delaysFilePath, e);
            } finally {
                log.info("Finished FeederTask.run() " + fileOffset);
            }
        }
    }

}
