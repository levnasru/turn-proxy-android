package com.freeturn.app.data.config

/**
 * Стандартный список российских доменов и сервисов для прямого доступа (мимо VPN / direct).
 * Соответствует defaultDirectDomains на десктопе (cmd/desktop/bypass.go).
 */
val DEFAULT_BYPASS_DOMAINS: List<String> = listOf(
    "*.ru",
    "*.su",
    "*.xn--p1ai",
    "yandex.ru",
    "ya.ru",
    "vk.com",
    "vkvideo.ru",
    "mail.ru",
    "gosuslugi.ru",
    "sberbank.ru",
    "sber.ru",
    "tbank.ru",
    "tinkoff.ru",
    "vtb.ru",
    "alfabank.ru",
    "ozon.ru",
    "wildberries.ru",
    "avito.ru",
    "kinopoisk.ru",
    "rutube.ru",
    "dzen.ru",
    "2gis.ru",
    "mos.ru",
    "spb.ru",
    "nalog.gov.ru",
    "cbr.ru"
)

data class ParsedBypassRules(
    val domains: List<String>,
    val cidrs: List<String>
)

/**
 * Нормализует отдельную запись (домен или IP-адрес/CIDR).
 * Убирает http://, https://, завершающие пути, пробелы.
 * Превращает одиночный IPv4 в x.x.x.x/32.
 */
fun normalizeBypassEntry(input: String): String {
    var s = input.trim()
    if (s.startsWith("#")) return ""
    if (s.contains("://")) s = s.substringAfter("://")
    if (s.contains("/") && !s.matches(Regex("^[0-9.]+/[0-9]+$"))) {
        s = s.substringBefore("/")
    }
    s = s.trim().lowercase()
    if (s.isBlank()) return ""

    val octets = s.split(".")
    if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && !s.contains("/")) {
        return "$s/32"
    }
    return s
}

/**
 * Парсит сырую строку правил (разделенных переводами строк, запятыми или пробелами)
 * на домены и IP/CIDR.
 */
fun parseBypassRules(raw: String?): ParsedBypassRules {
    if (raw.isNullOrBlank()) return ParsedBypassRules(emptyList(), emptyList())
    val domains = mutableListOf<String>()
    val cidrs = mutableListOf<String>()

    for (line in raw.lineSequence()) {
        val trimmedLine = line.trim()
        if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) continue
        val tokens = trimmedLine.split(",", " ")
        for (token in tokens) {
            val normalized = normalizeBypassEntry(token)
            if (normalized.isBlank()) continue

            if (normalized.contains("/")) {
                val parts = normalized.split("/")
                if (parts.size == 2 && parts[0].split(".").size == 4) {
                    cidrs += normalized
                    continue
                }
            }
            domains += normalized
        }
    }
    return ParsedBypassRules(domains = domains.distinct(), cidrs = cidrs.distinct())
}

/**
 * Добавляет новую запись в список правил без дубликатов.
 */
fun addBypassEntry(currentRules: String, newEntry: String): String {
    val normalized = normalizeBypassEntry(newEntry)
    if (normalized.isBlank()) return currentRules.trim()

    val currentList = currentRules.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { normalizeBypassEntry(it) }
        .filter { it.isNotBlank() }
        .toMutableList()

    if (currentList.none { it.equals(normalized, ignoreCase = true) }) {
        currentList.add(normalized)
    }
    return currentList.joinToString("\n")
}

/**
 * Удаляет запись из списка правил.
 */
fun removeBypassEntry(currentRules: String, target: String): String {
    val targetNorm = normalizeBypassEntry(target)
    val targetRaw = target.trim().lowercase()
    return currentRules.lineSequence()
        .map { it.trim() }
        .filter { line ->
            if (line.isBlank() || line.startsWith("#")) return@filter false
            val norm = normalizeBypassEntry(line)
            norm.isNotBlank() && !norm.equals(targetNorm, ignoreCase = true) && !norm.equals(targetRaw, ignoreCase = true)
        }
        .joinToString("\n")
}

/**
 * Добавляет стандартные российские сервисы к текущему списку правил.
 */
fun addDefaultBypassRules(currentRules: String): String {
    var result = currentRules
    for (d in DEFAULT_BYPASS_DOMAINS) {
        result = addBypassEntry(result, d)
    }
    return result
}
