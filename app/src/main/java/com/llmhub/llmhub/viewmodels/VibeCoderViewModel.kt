package com.llmhub.llmhub.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelAvailabilityProvider
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CodeLanguage {
    HTML, PYTHON, JAVASCRIPT, UNKNOWN
}

/**
 * VibeCoderViewModel handles code generation using LLM inference.
 * Users provide a prompt, and the model generates HTML/Python/JavaScript code.
 */
class VibeCoderViewModel(application: Application) : AndroidViewModel(application) {
    
    private val inferenceService = (application as com.llmhub.llmhub.LlmHubApplication).inferenceService
    private val prefs = application.getSharedPreferences("vibe_coder_prefs", Context.MODE_PRIVATE)
    
    private var processingJob: Job? = null
    
    // Available models
    private val _availableModels = MutableStateFlow<List<LLMModel>>(emptyList())
    val availableModels: StateFlow<List<LLMModel>> = _availableModels.asStateFlow()
    
    // Model selection & backend
    private val _selectedModel = MutableStateFlow<LLMModel?>(null)
    val selectedModel: StateFlow<LLMModel?> = _selectedModel.asStateFlow()
    
    private val _selectedBackend = MutableStateFlow<LlmInference.Backend?>(null)
    val selectedBackend: StateFlow<LlmInference.Backend?> = _selectedBackend.asStateFlow()
    
    // Loading states
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _isPlanning = MutableStateFlow(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()
    
    private var currentSpec: String = ""
    
    // Generated code & metadata
    private val _generatedCode = MutableStateFlow("")
    val generatedCode: StateFlow<String> = _generatedCode.asStateFlow()
    
    private val _codeLanguage = MutableStateFlow(CodeLanguage.UNKNOWN)
    val codeLanguage: StateFlow<CodeLanguage> = _codeLanguage.asStateFlow()
    
    private val _promptInput = MutableStateFlow("")
    val promptInput: StateFlow<String> = _promptInput.asStateFlow()
    
    // Error handling
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        loadAvailableModels()
        loadSavedSettings()
    }
    
    /**
     * Load previously saved settings (model, backend)
     */
    private fun loadSavedSettings() {
        val savedBackendName = prefs.getString("selected_backend", LlmInference.Backend.GPU.name)
        _selectedBackend.value = try {
            LlmInference.Backend.valueOf(savedBackendName ?: LlmInference.Backend.GPU.name)
        } catch (_: IllegalArgumentException) {
            LlmInference.Backend.GPU
        }
        
        val savedModelName = prefs.getString("selected_model_name", null)
        if (savedModelName != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(100)
                val model = _availableModels.value.find { it.name == savedModelName }
                if (model != null) {
                    _selectedModel.value = model
                    if (!model.supportsGpu && _selectedBackend.value == LlmInference.Backend.GPU) {
                        _selectedBackend.value = LlmInference.Backend.CPU
                    }
                }
            }
        }
    }
    
    /**
     * Save current model and backend preferences
     */
    private fun saveSettings() {
        prefs.edit().apply {
            putString("selected_model_name", _selectedModel.value?.name)
            putString("selected_backend", _selectedBackend.value?.name)
            apply()
        }
    }
    
    /**
     * Load all available models from device
     */
    private fun loadAvailableModels() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val available = ModelAvailabilityProvider.loadAvailableModels(context)
                .filter { it.category != "embedding" && !it.name.contains("Projector", ignoreCase = true) }
            _availableModels.value = available
            if (_selectedModel.value == null) {
                available.firstOrNull()?.let {
                    _selectedModel.value = it
                    _selectedBackend.value = if (it.supportsGpu) {
                        _selectedBackend.value ?: LlmInference.Backend.GPU
                    } else {
                        LlmInference.Backend.CPU
                    }
                }
            }
        }
    }
    
    /**
     * Select a different model for code generation
     */
    fun selectModel(model: LLMModel) {
        if (_isModelLoaded.value) {
            unloadModel()
        }
        
        _selectedModel.value = model
        _isModelLoaded.value = false
        
        _selectedBackend.value = if (model.supportsGpu) {
            _selectedBackend.value ?: LlmInference.Backend.GPU
        } else {
            LlmInference.Backend.CPU
        }
        
        saveSettings()
    }
    
    /**
     * Select inference backend (GPU, CPU, etc.)
     */
    fun selectBackend(backend: LlmInference.Backend) {
        if (_isModelLoaded.value) {
            unloadModel()
        }
        
        _selectedBackend.value = backend
        _isModelLoaded.value = false
        saveSettings()
    }
    
    /**
     * Load the selected model into memory
     */
    fun loadModel() {
        val model = _selectedModel.value ?: return
        val backend = _selectedBackend.value ?: return
        
        if (_isLoading.value || _isModelLoaded.value) {
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                inferenceService.unloadModel()
                
                // Load model with text-only mode (vibe coder generates code as text)
                val success = inferenceService.loadModel(
                    model = model,
                    preferredBackend = backend,
                    disableVision = true,
                    disableAudio = true
                )
                
                if (success) {
                    _isModelLoaded.value = true
                } else {
                    _errorMessage.value = "Failed to load model"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Unload the current model from memory
     */
    fun unloadModel() {
        viewModelScope.launch {
            try {
                inferenceService.unloadModel()
                _isModelLoaded.value = false
                _generatedCode.value = ""
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to unload model"
            }
        }
    }
    
    /**
     * Update the prompt input text
     */
    fun updatePromptInput(text: String) {
        _promptInput.value = text
    }
    
    /**
     * Generate code based on the user's prompt
     */
    fun generateCode(prompt: String) {
        if (prompt.isBlank()) return
        val model = _selectedModel.value ?: return
        
        if (!_isModelLoaded.value) {
            _errorMessage.value = "Please load a model first"
            return
        }
        
        processingJob?.cancel()
        
        processingJob = viewModelScope.launch {
            _isProcessing.value = true
            _generatedCode.value = ""
            _errorMessage.value = null
            
            // Determine if request is creative/game or utility/precise
            val isCreative = prompt.contains("game", ignoreCase = true) || 
                           prompt.contains("story", ignoreCase = true) ||
                           prompt.contains("art", ignoreCase = true) ||
                           prompt.contains("creative", ignoreCase = true)
            
            // Set optimized parameters based on intent:
            // - Utility/Math/Code (default): 0.2 temperature for high precision
            // - Games/Creative: 0.6 temperature for balanced creativity
            val temperature = if (isCreative) 0.6f else 0.2f
            
            inferenceService.setGenerationParameters(
                maxTokens = 8192,
                topK = 40,
                topP = 0.95f,
                temperature = temperature
            )
            
            try {
                // Step 1: Architect (Meta-Prompting)
                // If we have existing code and the prompt implies a revision, treat it as such.
                // Otherwise, treat as a new project/spec.
                val currentCode = _generatedCode.value
                val isRevision = currentCode.isNotBlank() && !prompt.equals("new", ignoreCase = true)
                
                _isPlanning.value = true
                var builtSpec = ""
                
                try {
                    // Timeout for planning phase (30 seconds)
                    // If it takes longer, we skip planning and go straight to coding
                    kotlinx.coroutines.withTimeout(30_000L) {
                        val specPrompt = buildSpecPrompt(prompt, if (isRevision) currentCode else "")
                        val specChatId = "vibe-spec-${UUID.randomUUID()}"
                        
                        // Generate Spec
                        val specResponseFlow = inferenceService.generateResponseStreamWithSession(
                            prompt = specPrompt,
                            model = model,
                            chatId = specChatId,
                            images = emptyList(),
                            audioData = null,
                            webSearchEnabled = false
                        )
                        
                        specResponseFlow.collect { token ->
                            builtSpec += token
                        }
                    }
                } catch (e: Exception) {
                    Log.w("VibeCoderVM", "Planning phase failed or timed out: ${e.message}. Falling back to direct generation.")
                    // Ensure session is reset if we timed out, to clear any stuck state
                    try {
                        inferenceService.resetChatSession("vibe-spec-cleanup")
                    } catch (resetEx: Exception) {
                        Log.e("VibeCoderVM", "Failed to reset session after planning failure", resetEx)
                    }
                }
                
                currentSpec = builtSpec
                _isPlanning.value = false
                
                // CRITICAL: Explicitly reset the session between Architect and Coder phases
                // This ensures the Coder phase starts with a clean slate and avoids "Previous invocation still processing"
                // or token limit issues from the large spec generation.
                // We use a different chat ID for the coder anyway, but resetting ensures the single shared session is ready.
                try {
                    inferenceService.resetChatSession("vibe-spec-handoff")
                    // Give it a moment to clear
                    kotlinx.coroutines.delay(200)
                } catch (e: Exception) {
                    Log.w("VibeCoderVM", "Session reset between phases failed: ${e.message}")
                }
                
                // Step 2: Coder (Implementation)
                // If spec is empty (failed), we fall back to a direct prompt strategy
                val implementationPrompt = if (builtSpec.isNotBlank()) {
                    buildImplementationPrompt(builtSpec, isRevision)
                } else {
                    // Fallback: Use the original direct prompt logic
                    buildPrompt(prompt)
                }
                
                val codeChatId = "vibe-coder-${UUID.randomUUID()}"
                
                val responseFlow = inferenceService.generateResponseStreamWithSession(
                    prompt = implementationPrompt,
                    model = model,
                    chatId = codeChatId,
                    images = emptyList(),
                    audioData = null,
                    webSearchEnabled = false
                )
                
                var responseText = ""
                responseFlow.collect { token ->
                    responseText += token
                    _generatedCode.value = responseText
                }
                
                // Detect code language and extract code from response
                detectAndExtractCode(responseText)
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("VibeCoderVM", "Generation cancelled")
            } catch (e: Exception) {
                val message = e.message ?: ""
                val shouldShowError = !message.contains("cancelled", ignoreCase = true) &&
                                    !message.contains("Previous invocation still processing", ignoreCase = true) &&
                                    !message.contains("StandaloneCoroutine", ignoreCase = true)
                
                if (shouldShowError) {
                    _errorMessage.value = message.ifBlank { "Generation failed" }
                    Log.e("VibeCoderVM", "Generation error: $message", e)
                } else {
                    Log.d("VibeCoderVM", "Suppressed error: $message")
                }
            } finally {
                // Reset parameters to defaults (null)
                inferenceService.setGenerationParameters(null, null, null, null)
                _isProcessing.value = false
                _isPlanning.value = false
                processingJob = null
            }
        }
    }
    
    /**
     * Detect code language and extract clean code from the response.
     * Supports HTML, Python, JavaScript wrapped in markdown code blocks or XML tags.
     * Handles edge cases where code block markers aren't perfectly formatted.
     */
    private fun detectAndExtractCode(response: String) {
        // Try to extract from markdown code blocks with language hints (```html, ```python, etc.)
        // Relaxed regex to allow immediate content after language tag (no newline required)
        val htmlMatch = Regex("```(?:html|htm)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(response)
        if (htmlMatch != null) {
            _generatedCode.value = htmlMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.HTML
            return
        }
        
        val pythonMatch = Regex("```(?:python|py)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(response)
        if (pythonMatch != null) {
            _generatedCode.value = pythonMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.PYTHON
            return
        }
        
        val jsMatch = Regex("```(?:javascript|js)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(response)
        if (jsMatch != null) {
            _generatedCode.value = jsMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.JAVASCRIPT
            return
        }
        
        // Fallback: Extract any content between ``` markers (handles malformed responses)
        val genericMatch = Regex("```\\s*([\\s\\S]*?)```").find(response)
        if (genericMatch != null) {
            val extracted = genericMatch.groupValues[1].trim()
            _generatedCode.value = extracted
            // Detect language from content
            when {
                extracted.contains("<!DOCTYPE", ignoreCase = true) || extracted.contains("<html", ignoreCase = true) -> {
                    _codeLanguage.value = CodeLanguage.HTML
                }
                extracted.contains("def ") || extracted.contains("import ") -> {
                    _codeLanguage.value = CodeLanguage.PYTHON
                }
                extracted.contains("function ") || extracted.contains("const ") -> {
                    _codeLanguage.value = CodeLanguage.JAVASCRIPT
                }
                else -> {
                    _codeLanguage.value = CodeLanguage.UNKNOWN
                }
            }
            return
        }
        
        // Try to extract from XML-like tags (fallback)
        val xmlHtmlMatch = Regex("<code[^>]*>([\\s\\S]*?)</code>", RegexOption.IGNORE_CASE).find(response)
        if (xmlHtmlMatch != null) {
            val extracted = xmlHtmlMatch.groupValues[1].trim()
            _generatedCode.value = extracted
            _codeLanguage.value = CodeLanguage.HTML
            return
        }
        
        // Default detection based on content pattern
        when {
            response.contains("<!DOCTYPE html", ignoreCase = true) ||
            response.contains("<html", ignoreCase = true) -> {
                _codeLanguage.value = CodeLanguage.HTML
            }
            response.contains("def ", ignoreCase = true) ||
            response.contains("import ", ignoreCase = true) ||
            response.contains("python", ignoreCase = true) -> {
                _codeLanguage.value = CodeLanguage.PYTHON
            }
            response.contains("function ", ignoreCase = true) ||
            response.contains("const ", ignoreCase = true) ||
            response.contains("var ", ignoreCase = true) ||
            response.contains("javascript", ignoreCase = true) -> {
                _codeLanguage.value = CodeLanguage.JAVASCRIPT
            }
            else -> {
                _codeLanguage.value = CodeLanguage.UNKNOWN
            }
        }
    }
    
    /**
     * Cancel ongoing code generation
     */
    fun cancelGeneration() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false
    }
    
    /**
     * Clear generated code
     */
    fun clearCode() {
        _generatedCode.value = ""
        _codeLanguage.value = CodeLanguage.UNKNOWN
        currentSpec = ""
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Build the Architect Spec Prompt (Step 1)
     */
    private fun buildSpecPrompt(userRequest: String, currentCode: String): String {
        val isRevision = currentCode.isNotBlank()
        return """
            You are a Senior Technical Product Manager and Software Architect.
            Your goal is to analyze the user's request and create a detailed technical specification for a developer to implement.
            
            CONTEXT:
            ${if (isRevision) "The user wants to MODIFY this existing code:\n$currentCode" else "This is a NEW project request."}
            
            USER REQUEST: "$userRequest"
            
            TASK:
            1. Analyze the request.
            2. If modifying, identify specific changes to the existing code while maintaining its structure.
            3. If new, plan the entire architecture.
            4. Create a "Vibe Specification" in Markdown.
            
            SPECIFICATION FORMAT:
            # [App Name]
            ## Core Functionality
            - [Feature 1]
            - [Feature 2]
            ## UI Architecture
            - [Element]: [ID] - [Description]
            - Layout structure (Container -> Header -> Content -> Footer)
            - History/Logs: [Required] Must include a visible list of previous actions/moves.
            - Visuals: Use SVG/Canvas for graphics (NO external images).
            ## Data State
            - [Variable Name]: [Type] - [Description] (e.g., score, historyLog, etc.)
            ## Logic Flow
            - [Action] -> [State Change] -> [UI Update]
            
            Output ONLY the specification. Do not write code.
        """.trimIndent()
    }

    /**
     * Build the Developer Implementation Prompt (Step 2)
     */
    private fun buildImplementationPrompt(spec: String, isRevision: Boolean): String {
        return """
            You are an expert developer who is adept at generating production-ready stand-alone apps and games in either HTML or Python. 
            Your task is to generate clean, functional code based on the Technical Specification provided below. The code will run in an offline interpreter.
            
            TECHNICAL SPECIFICATION:
            $spec
            
            Think about how to meet this specification for the best stand-alone functional code to delight the user.

            CONSTRAINTS:
            Generate code that is:
            - Syntactically correct and ready to run
            - Well-commented where appropriate
            - Self-contained (no external dependencies)
            
            CONSTRAINT: NO EXTERNAL RESOURCES
            - Do NOT use external images (<img> src must be data URI or SVG directly in code).
            - Do NOT use external scripts (CDNs) or CSS files.
            - Use standard HTML5/CSS3/ES6+ features.
            - For graphics, use inline SVG, Canvas API, or CSS shapes.
            - Provide a professional, polished look.
            
            REQUIREMENTS FOR APPS/GAMES (HTML/JS):
            - Create a complete, standalone Single Page Application (SPA).
            - ALWAYS include a "Reset" or "New" button to restart the application state.
            - Games should maintain a functional game state (Score, Win/Loss messages, turn history, etc.) in the UI. Turn history would be a list of previous moves/actions so the user can track progress, and summarize the results when the game is won or lost.
            - Ensure all interactive elements (buttons, inputs) are clearly visible and accessible.
            - FUNCTIONAL UI: Ensure ALL UI elements (including SVGs, Canvas) are functional and wired to the script. Do NOT add decorative elements that do nothing.
            - EVENT DRIVEN: Do NOT use blocking loops (while/for) to wait for user input. Use event listeners and state variables to handle user interactions asynchronously.
            
            REQUIREMENTS FOR UTILITY APPS (Calculators, Converters, Tools):
            - Use clear, labeled forms with appropriate input types (number, text, etc.).
            - Validate inputs before processing (show user-friendly error messages).
            - clearly display results in a distinct output area.
            - Ensure high precision for calculations.
            
            REQUIREMENTS FOR PYTHON:
            - Create a functional script (no external dependencies).
            - Since this runs in a text simulation check, use print() statements to simulate output/state.
            - For object simulations (e.g., "Park Sim"), create classes and a main execution block that demonstrates the logic.
            
            IMPORTANT:
            - If generating HTML/JavaScript, wrap it in a markdown code block: ```html
            YOUR HTML CODE HERE
            ```
            - If generating Python, wrap it in a markdown code block: ```python
            YOUR PYTHON CODE HERE
            ```
            - Respond ONLY with the production-ready stand-alone code in a markdown code block. DO NOT include explanations, warnings, or additional text before or after the code block.
    
        """.trimIndent()
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            inferenceService.onCleared()
        }
    }
}
