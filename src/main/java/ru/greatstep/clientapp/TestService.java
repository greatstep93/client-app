package ru.greatstep.clientapp;

import static java.lang.String.format;
import static java.lang.Thread.startVirtualThread;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TestService {

    private final WebClientHelper webClientHelper;
    private final Logger logger;

    @Value("${count}")
    private Integer count;
    @Value("${virtual}")
    private Boolean isVirtual;

    @PostConstruct
    public void start() {
        logger.info(format("start from %s requests and virtual threads %s", count, isVirtual ? "on" : "off"));
        long start = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            if (isVirtual) {
                startVirtualThread(request());
            } else {
                new Thread(request()).start();
            }

        }

        long end = System.currentTimeMillis();
        logger.info(format("Time: %sms", end - start));

        System.exit(-1);
    }

    private Runnable request() {
        return webClientHelper.getRequest("http://192.168.1.200:8080", Map.of(), String.class)::block;
    }

}
