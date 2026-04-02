package com.databricks.jg.ai_tracing_otel.controller;

import com.databricks.jg.ai_tracing_otel.service.TextAiService;
import com.databricks.jg.ai_tracing_otel.service.TextAiService.TextResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/api")
public class TextOperationsController {

    private final TextAiService textAiService;

    public TextOperationsController(TextAiService textAiService) {
        this.textAiService = textAiService;
    }

    @PostMapping("/summarize")
    public String summarize(@RequestParam String text, Model model) {
        TextResult result = textAiService.summarize(text);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }

    @PostMapping("/extract")
    public String extract(@RequestParam String text, Model model) {
        TextResult result = textAiService.extractTopics(text);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }

    @PostMapping("/tone")
    public String changeTone(
            @RequestParam String text,
            @RequestParam(defaultValue = "formal") String tone,
            Model model) {
        TextResult result = textAiService.changeTone(text, tone);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }
}
