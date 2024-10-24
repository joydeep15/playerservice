package com.app.playerservicejava.config;


import io.github.ollama4j.OllamaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ChatClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatClientConfiguration.class);

    // Once you start the Ollama docker container - Ollama server runs locally on port 11434 at http://127.0.0.1:11434/
    private String OLLAMA_HOST = "http://127.0.0.1:11434/";

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public OllamaAPI ollamaAPI() {
        return new OllamaAPI(OLLAMA_HOST);
    }

}
