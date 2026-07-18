package com.ashish.ragreferee.model;

import java.util.List;

public class RulingResponse {

    private final String verdict;          // the LLM's grounded answer
    private final List<String> citedSections; // which chunk labels were used

    public RulingResponse(String verdict, List<String> citedSections) {
        this.verdict = verdict;
        this.citedSections = citedSections;
    }

    public String getVerdict() {
        return verdict;
    }

    public List<String> getCitedSections() {
        return citedSections;
    }
}
