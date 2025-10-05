package com.jamesward.bravemcp

import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

@SpringBootApplication
@EnableConfigurationProperties(BraveProperties::class)
class Application(
    webClientBuilder: WebClient.Builder,
    private val braveProperties: BraveProperties
) {

    data class Summarizer(val key: String)

    data class BraveSearchResponse(val summarizer: Summarizer)

    private val client: WebClient = webClientBuilder
        .baseUrl("https://api.search.brave.com")
        .build()

    private val log = LoggerFactory.getLogger(Application::class.java)

    // Simple in-memory cache keyed by the search query
    private val summaryCache = ConcurrentHashMap<String, Mono<String>>()

    @McpTool(
        name = "brave_web_search_summary",
        description = "Performs web searches using the Brave Search API and returns comprehensive search results with rich metadata. When to use: - General web searches for information, facts, or current topics - Location-based queries (restaurants, businesses, points of interest) - News searches for recent events or breaking stories - Finding videos, discussions, or FAQ content - Research requiring diverse result types (web pages, images, reviews, etc.) Returns a JSON list of web results with title, description, and URL."
    )
    fun braveWebSearchSummary(@McpToolParam(required = true, description = "search query (max 400 chars, 50 words") query: String): Mono<String> = run {
        log.info("Search query: $query")

        // Normalize the key a bit to reduce duplicates due to whitespace
        val key = query.trim().lowercase()

        summaryCache.computeIfAbsent(key) {
            log.info("Cache miss for $key")

            client
                .get()
                .uri {
                    it.path("/res/v1/web/search")
                        .queryParam("q", key)
                        .queryParam("summary", "true")
                        .build()
                }
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .header("X-Subscription-Token", braveProperties.apikey)
                .retrieve()
                .bodyToMono<BraveSearchResponse>()
                .flatMap { webSearchResponse ->
                    log.info("For query $query, getting summary: ${webSearchResponse.summarizer.key}")

                    val encodedKey = URLEncoder.encode(webSearchResponse.summarizer.key, StandardCharsets.UTF_8)

                    client
                        .get()
                        .uri {
                            it.path("/res/v1/summarizer/search")
                                .queryParam("key", encodedKey)
                                .build()
                        }
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                        .header("X-Subscription-Token", braveProperties.apikey)
                        .retrieve()
                        .bodyToMono<String>()
                }
                // Cache the terminal result; remove on error so a future call can retry
                .doOnError {
                    log.error("Error while fetching web search results: ${it.message}", it)
                    summaryCache.remove(key)
                }
                .doOnSuccess {
                    log.info("Successfully fetched summary for: $query")
                }
                .cache()
        }
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
