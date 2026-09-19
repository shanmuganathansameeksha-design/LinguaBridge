// LinguaBridge frontend v1.10
//
// Bug fix: source and target are read from the dropdowns AT CLICK TIME,
// inside translate(). They are never cached in variables at page load,
// so changing a dropdown always affects the very next request.
// All three values (text, source, target) are sent with EVERY request.

"use strict";

document.addEventListener("DOMContentLoaded", () => {

    const translateButton = document.getElementById("translateButton");
    const outputText = document.getElementById("outputText");

    translateButton.addEventListener("click", translate);

    async function translate() {
        // Read the CURRENT values fresh on every click - never reuse old ones.
        const text = document.getElementById("inputText").value.trim();
        const source = document.getElementById("sourceLanguage").value;
        const target = document.getElementById("targetLanguage").value;

        if (!text) {
            showOutput("Please enter some text to translate.", "idle");
            return;
        }

        translateButton.disabled = true;
        showOutput("Translating…", "idle");

        try {
            const response = await fetch("http://localhost:8080/translate", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    text: text,
                    source: source,
                    target: target
                })
            });

            let data = null;
            try {
                data = await response.json();
            } catch (parseError) {
                showOutput("Server sent an unreadable response.", "error");
                return;
            }

            if (!response.ok) {
                showOutput("Error: " + (data && data.error ? data.error : "HTTP " + response.status), "error");
                return;
            }

            showOutput(data.translation, "result");

        } catch (networkError) {
            showOutput("Could not reach the server. Is TranslatorServer running on http://localhost:8080 ?", "error");
        } finally {
            translateButton.disabled = false;
        }
    }

    function showOutput(message, state) {
        outputText.textContent = message;
        outputText.classList.remove("idle", "result", "error");
        outputText.classList.add(state);
    }
});