package com.networknt.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.*;
import com.networknt.config.Config;
import com.networknt.utility.ModuleRegistry;
import org.apache.commons.lang.StringUtils;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility to mask sensitive data based on regex pattern before logging
 */
public class Mask {

    static Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private static final String MASK_CONFIG = "mask";
    public static final String MASK_REPLACEMENT_CHAR = "*";
    public static final String MASK_TYPE_STRING = "string";
    public static final String MASK_TYPE_REGEX = "regex";
    public static final String MASK_TYPE_JSON = "json";

    static final Logger logger = LoggerFactory.getLogger(Mask.class);
    private static Map<String, Object> config = null;

    static {
        config = Config.getInstance().getJsonMapConfigNoCache(MASK_CONFIG);
        ModuleRegistry.registerModule(Mask.class.getName(), config, null);
    }

    /**
     * Mask the input string with a list of patterns indexed by key in string section in mask.json
     * This is usually used to mask header values, query parameters and uri parameters
     *
     * @param input String The source of the string that needs to be masked
     * @param key   String The key that maps to a list of patterns for masking in config file
     * @return Masked result
     */
    public static String maskString(String input, String key) {
        String output = input;
        Map<String, Object> stringConfig = (Map<String, Object>) config.get(MASK_TYPE_STRING);
        if (stringConfig != null) {
            Map<String, Object> keyConfig = (Map<String, Object>) stringConfig.get(key);
            if (keyConfig != null) {
                Set<String> patterns = keyConfig.keySet();
                for (String pattern : patterns) {
                    output = output.replaceAll(pattern, (String) keyConfig.get(pattern));
                }
            }
        }
        return output;
    }

    /**
     * Replace a string input with a pattern found in regex section with key as index. Usually,
     * it is used to replace header, query parameter, uri parameter to same length of stars(*)
     *
     * @param input String The source of the string that needs to be masked
     * @param key   String The key maps to a list of name to pattern pair
     * @param name  String The name of the pattern in the key list
     * @return String Masked result
     */
    public static String maskRegex(String input, String key, String name) {
        String output = input;
        Map<String, Object> regexConfig = (Map<String, Object>) config.get(MASK_TYPE_REGEX);
        if (regexConfig != null) {
            Map<String, Object> keyConfig = (Map<String, Object>) regexConfig.get(key);
            if (keyConfig != null) {
                String regex = (String) keyConfig.get(name);
                if (regex != null && regex.length() > 0) {
                    return replaceWithMask(input, MASK_REPLACEMENT_CHAR.charAt(0), regex);
                }
            }
        }
        return output;
    }

    /**
     * Replace values in JSON using json path
     * @param input String The source of the string that needs to be masked
     * @param key String The key maps to a list of json path for masking
     * @return String Masked result
     */
    public static String maskJson(String input, String key) {
        String output = input;
        DocumentContext ctx = JsonPath.parse(input);
        Map<String, Object> jsonConfig = (Map<String, Object>) config.get(MASK_TYPE_JSON);
        if (jsonConfig != null) {
            Map<String, Object> patternMap = (Map<String, Object>) jsonConfig.get(key);
            if (patternMap != null) {
                JsonNode configNode = Config.getInstance().getMapper().valueToTree(patternMap);
                Iterator<Map.Entry<String, JsonNode>> iterator = configNode.fields();
                while (iterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    applyMask(entry, ctx);
                }
                return ctx.jsonString();
            } else {
                logger.warn("mask.json doesn't contain the key {} ", Encode.forJava(key));
                return output;
            }
        }
        return output;
    }

    private static void applyMask(Map.Entry<String, JsonNode> entry, DocumentContext ctx) {
        Object value;
        String jsonPath = entry.getKey();
        try {
            value = ctx.read(jsonPath);
            if (!(value instanceof String || value instanceof Integer || value instanceof List<?>)) {
                logger.error("The value specified by path {} cannot be masked", jsonPath);
                return;
            } else {
                if (!(value instanceof List<?>)) {
                    ctx.set(jsonPath, replaceWithMask(value.toString(), MASK_REPLACEMENT_CHAR.charAt(0), entry.getValue().asText()));
                } else {
                    maskList(ctx, jsonPath, entry.getValue().asText());
                }
            }
        } catch (PathNotFoundException e) {
            logger.warn("JsonPath {} could not be found.", jsonPath);
        }
        return;
    }


    private static String replaceWithMask(String stringToBeMasked, char maskingChar, String regex) {
        if (stringToBeMasked.length() == 0) {
            return stringToBeMasked;
        }
        String replacementString = "";
        String padGroup = "";
        if (!StringUtils.isEmpty(regex)) {
            try {
                Pattern pattern = patternCache.get(regex);
                if (pattern == null) {
                    pattern = Pattern.compile(regex);
                    patternCache.put(regex, pattern);
                }
                Matcher matcher = pattern.matcher(stringToBeMasked);
                if (matcher.matches()) {
                    String currentGroup = "";
                    for (int i = 0; i < matcher.groupCount(); i++) {
                        currentGroup = matcher.group(i + 1);
                        padGroup = StringUtils.rightPad("", currentGroup.length(), maskingChar);
                        stringToBeMasked = StringUtils.replace(stringToBeMasked, currentGroup, padGroup, 1);
                    }
                    replacementString = stringToBeMasked;
                }
            } catch (Exception e) {
                replacementString = StringUtils.rightPad("", stringToBeMasked.length(), maskingChar);
            }
        } else {
            replacementString = StringUtils.rightPad("", stringToBeMasked.length(), maskingChar);
        }
        return replacementString;
    }

    private static void maskList(DocumentContext ctx, String jsonPath, String expression) {
        ctx.configuration().addOptions(Option.AS_PATH_LIST);
        Configuration conf = Configuration.builder().options(Option.AS_PATH_LIST).build();
        DocumentContext context = JsonPath.using(conf).parse(ctx.jsonString());
        List<String> pathList = context.read(jsonPath);
        for (Iterator iterator = pathList.iterator(); iterator.hasNext(); ) {
            String path = (String) iterator.next();
            Object value = ctx.read(path);
            ctx.set(path, replaceWithMask(value.toString(), MASK_REPLACEMENT_CHAR.charAt(0), expression));
        }
    }
}