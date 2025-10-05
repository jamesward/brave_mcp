package com.jamesward.bravemcp

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "brave")
data class BraveProperties(
    var apikey: String? = null
)
