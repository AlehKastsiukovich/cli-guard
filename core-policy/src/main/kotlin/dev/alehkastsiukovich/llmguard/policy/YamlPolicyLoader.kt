package dev.alehkastsiukovich.llmguard.policy

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class YamlPolicyLoader : PolicyLoader {
    private val mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder(YAMLFactory())
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

    override fun load(path: Path): LlmGuardPolicy {
        if (!path.exists()) {
            throw PolicyValidationException("Policy file does not exist: $path")
        }

        Files.newBufferedReader(path).use { reader ->
            val policy = mapper.readValue<LlmGuardPolicy>(reader)
            validate(policy)
            return policy
        }
    }

    private fun validate(policy: LlmGuardPolicy) {
        require(policy.version == 1) {
            "Unsupported policy version ${policy.version}. Expected version 1."
        }

        require(policy.rules.map { it.id }.distinct().size == policy.rules.size) {
            "Rule identifiers must be unique."
        }

        policy.rules.forEach { rule ->
            require(rule.id.isNotBlank()) { "Rule id must not be blank." }
            require(rule.match.isNotEmpty()) { "Rule ${rule.id} must define at least one matcher." }
            if (rule.action.type == RuleActionType.REDACT) {
                val replacement = rule.action.replacement ?: policy.defaults.redactReplacement
                require(replacement.isNotBlank()) {
                    "Rule ${rule.id} uses redact action but replacement is blank."
                }
            }
        }
    }
}

fun MatchCriteria.isNotEmpty(): Boolean = paths.isNotEmpty() ||
    pathRegex.isNotEmpty() ||
    contentRegex.isNotEmpty() ||
    kotlin != null

class PolicyValidationException(message: String) : IllegalArgumentException(message)
