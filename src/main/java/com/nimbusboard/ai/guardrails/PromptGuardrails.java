package com.nimbusboard.ai.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Input and output guardrails for the Ask ThinkBoard feature.
 *
 * <p>The board only renders diagrams, so anything that is not a diagram request is refused before
 * it reaches the model. Checks run in severity order: structural, then injection, then harmful
 * content, then topicality.
 *
 * <p>Security-related architecture prompts ("DDoS protection", "fraud detection pipeline") are
 * legitimate and common on this product, so the abuse categories are skipped when the prompt reads
 * as defensive. See {@link #hasDefensiveContext(String)}.
 */
@Slf4j
@Component
public class PromptGuardrails {

    public static final int MAX_PROMPT_LENGTH = 2000;
    private static final int MIN_PROMPT_LENGTH = 3;

    /** Characters used to smuggle instructions past naive filters. */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\p{Cf}\\p{Co}]");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final List<Pattern> INJECTION = compile(
            "ignore\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier|preceding)\\s+(instruction|instructions|prompt|prompts|rule|rules|message|messages)",
            "disregard\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier|your)\\s+(instruction|instructions|prompt|prompts|rule|rules)",
            "forget\\s+(everything|all\\s+(previous|prior)|your\\s+(instruction|instructions|rules|training))",
            "(system|initial|original|hidden|secret|internal)\\s+prompt",
            "(reveal|show|print|repeat|output|display|expose|leak|reproduce|dump)\\s+(me\\s+)?(your|the)\\s+(system|initial|original|hidden|secret|internal)?\\s*(prompt|instruction|instructions|rules|configuration|guidelines)",
            "what\\s+(are|were)\\s+your\\s+(exact\\s+)?(instruction|instructions|rules|guidelines|system\\s+prompt)",
            "repeat\\s+(the\\s+)?(text|words|everything|content)\\s+(above|before)",
            "you\\s+are\\s+now\\s+(a|an|no\\s+longer)",
            "act\\s+as\\s+(a|an)\\s+(dan|jailbroken|unfiltered|unrestricted|uncensored|evil|amoral)",
            "(jailbreak|dan\\s+mode|developer\\s+mode|god\\s+mode)",
            "(bypass|override|ignore|circumvent|disable|turn\\s+off)\\s+(your\\s+|the\\s+|all\\s+)?(safety|filter|filters|guardrail|guardrails|restriction|restrictions|rule|rules|policy|policies)",
            "pretend\\s+(you|to\\s+be)\\s+(are\\s+)?(not\\s+)?(an?\\s+)?(ai|bound|restricted|limited)",
            "new\\s+(instruction|instructions|system\\s+message)\\s*:",
            // Chat-template tokens survive sanitizing (which strips the angle brackets), so match bare.
            "\\b(im_start|im_end|endoftext)\\b",
            "^\\s*(#{2,}\\s*)?(system|assistant)\\s*:",
            "\\brole\\s*[:=]\\s*[\"']?system"
    );

    private static final List<Pattern> WEAPONS = compile(
            "how\\s+(to|do\\s+i)\\s+(make|build|construct|assemble)\\s+(a\\s+|an\\s+)?(bomb|explosive|explosives|grenade|landmine|ied)",
            "(pipe|nail|dirty|pressure\\s+cooker)\\s+bomb",
            "(make|build|synthesize|manufacture)\\s+(napalm|thermite|c4|semtex|nerve\\s+agent|sarin|ricin|anthrax)",
            "(ghost\\s+gun|untraceable\\s+(gun|firearm|weapon))",
            "(3d\\s*-?\\s*print|print)\\s+(a\\s+)?(gun|firearm|receiver)",
            "(build|make|convert)\\s+(a\\s+)?(silencer|suppressor|full\\s+auto|machine\\s+gun)",
            "convert\\s+.{0,20}\\s+to\\s+full\\s*-?\\s*auto"
    );

    private static final List<Pattern> DRUGS = compile(
            "how\\s+(to|do\\s+i)\\s+(make|cook|synthesize|manufacture|produce|extract)\\s+(meth|methamphetamine|crystal\\s+meth|cocaine|crack|heroin|fentanyl|lsd|mdma|ecstasy)",
            "(synthesis|synthesise|recipe)\\s+(of|for)\\s+(meth|methamphetamine|cocaine|heroin|fentanyl|lsd|mdma)",
            "(buy|sell|order|ship)\\s+(illegal\\s+)?(drugs|cocaine|heroin|meth|fentanyl)\\s+(online|anonymously|on\\s+the\\s+dark)",
            "(grow|cultivate)\\s+.{0,15}\\s+without\\s+getting\\s+caught",
            "dark\\s*net\\s+(drug\\s+)?market(place)?\\s+(setup|guide|how)"
    );

    private static final List<Pattern> CYBER_ABUSE = compile(
            "(write|create|build|generate|code|develop)\\s+(me\\s+)?(a\\s+|an\\s+|some\\s+)?(malware|ransomware|virus|trojan|worm|rootkit|keylogger|spyware|botnet|backdoor)",
            "how\\s+(to|do\\s+i)\\s+(hack|breach|compromise|break)\\s+(into\\s+)?(a\\s+|an\\s+|someone|somebody|my\\s+(ex|neighbor|school|employer))",
            "hack\\s+(into\\s+)?(someone|somebody|my\\s+(ex|neighbour|neighbor|friend|school|employer)|a\\s+(bank|government))",
            "(launch|perform|carry\\s+out|execute|conduct)\\s+(a\\s+)?(ddos|dos|brute\\s*force|sql\\s+injection|phishing|ransomware)\\s+(attack|campaign)",
            "(ddos|dos|brute\\s*force|phishing|ransomware)\\s+attack\\s+(on|against)\\s+",
            "(steal|harvest|exfiltrate|dump)\\s+(user\\s+|customer\\s+|someone'?s\\s+)?(password|passwords|credential|credentials|credit\\s+card|card\\s+numbers|identity)",
            "(build|create|make|host)\\s+(a\\s+)?phishing\\s+(page|site|kit|email|campaign)",
            "(crack|bypass|defeat)\\s+(a\\s+)?(password|licence|license|drm|paywall|2fa|two[\\s-]factor|authentication)",
            "(carding|card\\s+skimming|credential\\s+stuffing\\s+tool)",
            "(spy\\s+on|track|stalk)\\s+(my\\s+)?(wife|husband|partner|girlfriend|boyfriend|ex|someone|somebody)\\s+(without|secretly)",
            "keylogger\\s+(for|to)\\s+"
    );

    private static final List<Pattern> FRAUD = compile(
            "how\\s+(to|do\\s+i)\\s+(launder|laundering)\\s+money",
            "(launder|laundering)\\s+(drug\\s+|dirty\\s+)?money\\s+(without|through|scheme|guide)",
            "(evade|avoid|dodge)\\s+(paying\\s+)?taxes\\s+(illegally|without\\s+getting\\s+caught)",
            "(make|create|forge|produce)\\s+(a\\s+)?(fake|counterfeit|forged)\\s+(passport|id|identity|licence|license|currency|money|certificate|diploma)",
            "(ponzi|pyramid)\\s+scheme\\s+(setup|guide|how\\s+to)",
            "(insider\\s+trading|bribe\\s+an?\\s+official)\\s+(guide|how\\s+to|without)"
    );

    private static final List<Pattern> SELF_HARM = compile(
            "how\\s+(to|do\\s+i|can\\s+i)\\s+(kill\\s+myself|commit\\s+suicide|end\\s+my\\s+life|hang\\s+myself|overdose)",
            "(painless|easiest|best|quickest)\\s+way\\s+to\\s+(die|kill\\s+myself|commit\\s+suicide)",
            "suicide\\s+(method|methods|note|guide|instructions)",
            "how\\s+(to|do\\s+i)\\s+(cut|starve|hurt|harm)\\s+myself"
    );

    private static final List<Pattern> VIOLENCE = compile(
            "how\\s+(to|do\\s+i)\\s+(kill|murder|poison|assassinate|torture|kidnap)\\s+(a\\s+|an\\s+|my\\s+|someone|somebody|people|him|her|them)",
            "(plan|planning|organize)\\s+(a\\s+)?(mass\\s+shooting|school\\s+shooting|terrorist\\s+attack|bombing|massacre)",
            "(get\\s+away\\s+with|commit)\\s+(a\\s+)?(murder|homicide)",
            "(hire|find)\\s+(a\\s+)?hit\\s*man",
            "(join|support|fund|recruit\\s+for)\\s+(isis|al\\s*-?\\s*qaeda|a\\s+terrorist\\s+(group|cell))"
    );

    private static final List<Pattern> SEXUAL_MINORS = compile(
            "(child|minor|underage|kid|teen)\\s+(porn|pornography|sexual|nude|nudes|erotica)",
            "(csam|cp\\s+links|loli(con)?|jailbait)",
            "sexual(ly)?\\s+(explicit\\s+)?(content\\s+)?(with|involving|of)\\s+(a\\s+)?(child|children|minor|minors|underage)"
    );

    private static final List<Pattern> HATE = compile(
            "(kill|exterminate|gas|lynch|eradicate|cleanse)\\s+all\\s+(the\\s+)?(jews|muslims|christians|blacks|whites|asians|gays|immigrants|women|men)",
            "(genocide|ethnic\\s+cleansing)\\s+(of|against)\\s+",
            "(why|prove)\\s+.{0,25}\\s+are\\s+(subhuman|inferior|vermin|animals)",
            "(write|generate|create)\\s+(a\\s+)?(racist|sexist|homophobic|antisemitic)\\s+(joke|rant|slur|insult|manifesto)",
            "(harass|dox|doxx)\\s+(someone|somebody|this\\s+person|my\\s+)"
    );

    /**
     * Requests the board cannot serve at all. Kept to unmistakable non-diagram intents so that
     * terse domain prompts ("url shortener", "payment flow") always pass.
     */
    private static final List<Pattern> OFF_TOPIC = compile(
            "write\\s+(me\\s+)?(a|an|the)?\\s*(poem|song|lyrics|haiku|essay|novel|story|screenplay|blog\\s+post|article|cover\\s+letter|resume|cv|tweet|caption)",
            "(translate|summarize|summarise|proofread|rewrite|paraphrase)\\s+(this|the\\s+following|my)\\s+",
            "(what'?s|what\\s+is)\\s+the\\s+weather",
            "tell\\s+me\\s+a\\s+(joke|story|secret)",
            "(who|what|when|where)\\s+(is|was|are|were)\\s+the\\s+(president|capital|population|prime\\s+minister)",
            "\\brecipe\\s+for\\b",
            "how\\s+to\\s+(cook|bake|fry|grill)\\b",
            "(solve|calculate|compute)\\s+(this|the\\s+following)?\\s*(math|equation|integral|derivative|homework)",
            "(give|write)\\s+me\\s+(the\\s+)?(full\\s+)?(source\\s+)?code\\s+(for|to)\\s+",
            "(medical|legal|financial|investment)\\s+advice",
            "(should\\s+i\\s+(buy|sell|invest)|stock\\s+tips?)\\b"
    );

    /**
     * Defensive or engineering framing, used to soften two categories of false positive:
     * security/anti-fraud systems are normal things to diagram ("ransomware detection pipeline"),
     * and off-topic wording often appears inside a legitimate domain ("HLD for a financial advice
     * platform"). Present here, the abuse and off-topic checks stand down.
     */
    private static final Pattern TECHNICAL_CONTEXT = Pattern.compile(
            "\\b(detect|detection|protect|protection|prevent|prevention|mitigat\\w*|defen[cs]\\w*|monitor\\w*|"
                    + "block\\w*|filter\\w*|scan\\w*|audit\\w*|complian\\w*|secure|security|firewall|waf|"
                    + "architecture|diagram|system\\s+design|hld|pipeline|microservice\\w*|infrastructure)\\b");

    /** Fragments of our own system prompts. If the model echoes these, the response is suppressed. */
    private static final List<String> SYSTEM_PROMPT_MARKERS = List.of(
            "you are an architecture diagram expert",
            "syntax rules (mandatory)",
            "architecture rules (mandatory)",
            "output only the mermaid",
            "system_prompt",
            "you are a flowchart expert",
            "you are a uml class diagram expert");

    private final List<Pattern> extraBlockedTerms;

    public PromptGuardrails(@Value("${app.ai.guardrails.blocked-terms:}") String blockedTermsCsv) {
        this.extraBlockedTerms = parseExtraTerms(blockedTermsCsv);
        if (!extraBlockedTerms.isEmpty()) {
            log.info("Loaded {} additional guardrail term(s) from configuration", extraBlockedTerms.size());
        }
    }

    /** Validates and normalizes a user prompt before it reaches the model. */
    public GuardrailResult checkPrompt(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return GuardrailResult.block(GuardrailCategory.EMPTY,
                    "Please describe the diagram you want to generate.");
        }

        String sanitized = sanitize(rawPrompt);
        if (sanitized.length() < MIN_PROMPT_LENGTH) {
            return GuardrailResult.block(GuardrailCategory.EMPTY,
                    "That prompt is too short. Describe the system or process you want diagrammed.");
        }
        if (sanitized.length() > MAX_PROMPT_LENGTH) {
            return GuardrailResult.block(GuardrailCategory.TOO_LONG,
                    "That prompt is too long. Keep it under " + MAX_PROMPT_LENGTH
                            + " characters and describe just the diagram you need.");
        }

        String probe = sanitized.toLowerCase();

        if (matchesAny(probe, INJECTION)) {
            return GuardrailResult.block(GuardrailCategory.PROMPT_INJECTION,
                    "This assistant only generates diagrams and cannot change its instructions or reveal them. "
                            + "Try describing the system or process you want to diagram.");
        }
        if (matchesAny(probe, extraBlockedTerms)) {
            return GuardrailResult.block(GuardrailCategory.ILLEGAL_ACTIVITY, refusal());
        }
        if (matchesAny(probe, SEXUAL_MINORS)) {
            return GuardrailResult.block(GuardrailCategory.SEXUAL_MINORS, refusal());
        }
        if (matchesAny(probe, SELF_HARM)) {
            return GuardrailResult.block(GuardrailCategory.SELF_HARM,
                    "This tool can't help with that. If you're struggling, please reach out to a local crisis line "
                            + "or someone you trust. ThinkBoard can only generate technical diagrams.");
        }
        if (matchesAny(probe, WEAPONS)) {
            return GuardrailResult.block(GuardrailCategory.WEAPONS, refusal());
        }
        if (matchesAny(probe, DRUGS)) {
            return GuardrailResult.block(GuardrailCategory.DRUGS, refusal());
        }
        if (matchesAny(probe, VIOLENCE)) {
            return GuardrailResult.block(GuardrailCategory.ILLEGAL_ACTIVITY, refusal());
        }
        if (matchesAny(probe, HATE)) {
            return GuardrailResult.block(GuardrailCategory.HATE_HARASSMENT, refusal());
        }

        boolean technical = hasTechnicalContext(probe);
        if (!technical && matchesAny(probe, CYBER_ABUSE)) {
            return GuardrailResult.block(GuardrailCategory.CYBER_ABUSE,
                    "ThinkBoard can diagram security systems (threat detection, WAF, fraud pipelines) "
                            + "but can't help carry out an attack. Try rephrasing around the system you're designing.");
        }
        if (!technical && matchesAny(probe, FRAUD)) {
            return GuardrailResult.block(GuardrailCategory.ILLEGAL_ACTIVITY, refusal());
        }

        if (!technical && matchesAny(probe, OFF_TOPIC)) {
            return GuardrailResult.block(GuardrailCategory.OFF_TOPIC,
                    "ThinkBoard only generates diagrams — architecture (HLD), flowcharts, and UML class diagrams. "
                            + "Describe a system or process and it will draw it on the board.");
        }

        return GuardrailResult.allow(sanitized);
    }

    /** Rejects model output that leaked instructions instead of returning a diagram. */
    public GuardrailResult checkOutput(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return GuardrailResult.block(GuardrailCategory.OUTPUT_INVALID,
                    "The AI returned an empty diagram. Try rephrasing your prompt.");
        }
        String lower = mermaid.toLowerCase();
        for (String marker : SYSTEM_PROMPT_MARKERS) {
            if (lower.contains(marker)) {
                log.warn("Blocked AI output containing system prompt marker: {}", marker);
                return GuardrailResult.block(GuardrailCategory.OUTPUT_LEAK,
                        "The AI response was blocked by a safety check. Please try a different prompt.");
            }
        }
        return GuardrailResult.allow(mermaid);
    }

    /**
     * Normalizes away the tricks used to smuggle instructions: compatibility-normalizes unicode
     * lookalikes, drops zero-width and control characters, and collapses whitespace runs.
     */
    public String sanitize(String prompt) {
        String normalized = Normalizer.normalize(prompt, Normalizer.Form.NFKC);
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");
        normalized = CONTROL_CHARS.matcher(normalized).replaceAll(" ");
        // Angle brackets and braces double as chat-template and Mermaid shape delimiters.
        normalized = normalized.replaceAll("[<>{}]", "");
        normalized = WHITESPACE_RUN.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }

    private boolean hasTechnicalContext(String probe) {
        return TECHNICAL_CONTEXT.matcher(probe).find();
    }

    private static String refusal() {
        return "That request can't be diagrammed here. ThinkBoard generates architecture, flowchart, "
                + "and UML class diagrams for software systems.";
    }

    private static boolean matchesAny(String probe, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(probe).find()) return true;
        }
        return false;
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> compiled = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return List.copyOf(compiled);
    }

    private static List<Pattern> parseExtraTerms(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(term -> Pattern.compile("\\b" + Pattern.quote(term) + "\\b", Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
