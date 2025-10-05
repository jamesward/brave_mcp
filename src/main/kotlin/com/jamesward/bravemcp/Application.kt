package com.jamesward.bravemcp

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

@SpringBootApplication
@EnableConfigurationProperties(BraveProperties::class)
class Application(
    webClientBuilder: WebClient.Builder,
    private val braveProperties: BraveProperties
) {

    data class WebResult(
        val title: String?,
        val url: String?,
        val description: String?
    )

    data class WebSearchResults(
        val web: WebResults?
    )

    data class WebResults(
        val results: List<WebResult>?
    )

    private val client: WebClient = webClientBuilder
        .baseUrl("https://api.search.brave.com")
        .build()

    private val log = LoggerFactory.getLogger(Application::class.java)
    private val objectMapper = ObjectMapper()

    // Simple in-memory cache keyed by the search query
    private val searchCache = ConcurrentHashMap<String, Mono<String>>()

    @McpTool(
        name = "brave_web_search_summary",
        description = "Performs web searches using the Brave Search API and returns comprehensive search results with rich metadata. When to use: - General web searches for information, facts, or current topics - Location-based queries (restaurants, businesses, points of interest) - News searches for recent events or breaking stories - Finding videos, discussions, or FAQ content - Research requiring diverse result types (web pages, images, reviews, etc.) Returns a JSON list of web results with title, description, and URL."
    )
    fun braveWebSearchSummary(@McpToolParam(required = true, description = "search query (max 400 chars, 50 words") query: String): Mono<String> = run {
        log.info("Search query: $query")

        // Normalize the key a bit to reduce duplicates due to whitespace
        val key = query.trim().lowercase()

        searchCache.computeIfAbsent(key) {
            log.info("Cache miss for $key")

            client
                .get()
                .uri {
                    it.path("/res/v1/web/search")
                        .queryParam("q", key)
                        .queryParam("count", "10")
                        .build()
                }
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .header("X-Subscription-Token", braveProperties.apikey)
                .retrieve()
                .bodyToMono<WebSearchResults>()
                .map { response ->
                    val results = response.web?.results ?: emptyList()
                    if (results.isEmpty()) {
                        log.warn("No search results found for query: $query")
                        "No search results found for this query."
                    } else {
                        log.info("Found ${results.size} results for query: $query")
                        // Format results as JSON string
                        objectMapper.writeValueAsString(results.take(5).map { result ->
                            mapOf(
                                "title" to (result.title ?: ""),
                                "url" to (result.url ?: ""),
                                "description" to (result.description ?: "")
                            )
                        })
                    }
                }
                // Cache the terminal result; remove on error so a future call can retry
                .doOnError {
                    log.error("Error while fetching web search results: ${it.message}", it)
                    searchCache.remove(key)
                }
                .doOnSuccess {
                    log.info("Successfully fetched search results for: $query")
                }
                .cache()
        }
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
