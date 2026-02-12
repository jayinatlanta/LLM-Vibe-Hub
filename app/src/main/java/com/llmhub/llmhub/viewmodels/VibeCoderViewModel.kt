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
            
            try {
                val fullPrompt = buildPrompt(prompt)
                val chatId = "vibe-coder-${UUID.randomUUID()}"
                
                val responseFlow = inferenceService.generateResponseStreamWithSession(
                    prompt = fullPrompt,
                    model = model,
                    chatId = chatId,
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
                _isProcessing.value = false
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
        val htmlMatch = Regex("```(?:html|htm)\\s*\\n([\\s\\S]*?)```").find(response)
        if (htmlMatch != null) {
            _generatedCode.value = htmlMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.HTML
            return
        }
        
        val pythonMatch = Regex("```(?:python|py)\\s*\\n([\\s\\S]*?)```").find(response)
        if (pythonMatch != null) {
            _generatedCode.value = pythonMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.PYTHON
            return
        }
        
        val jsMatch = Regex("```(?:javascript|js)\\s*\\n([\\s\\S]*?)```").find(response)
        if (jsMatch != null) {
            _generatedCode.value = jsMatch.groupValues[1].trim()
            _codeLanguage.value = CodeLanguage.JAVASCRIPT
            return
        }
        
        // Fallback: Extract any content between ``` markers (handles malformed responses)
        // Allow optional newline after opening ``` and before closing ```
        val genericMatch = Regex("```\\s*\\n?([\\s\\S]*?)```").find(response)
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
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Build the system prompt for code generation
     */
    private fun buildPrompt(userPrompt: String): String {
        return """
            You are an expert code generation AI. Your task is to generate clean, functional code based on user requests.
            
            Generate code that is:
            - Syntactically correct and ready to run
            - Well-commented where appropriate
            - Self-contained (no external dependencies unless absolutely necessary)
            - For HTML/JavaScript: Create a complete, standalone Single Page Application (SPA) that works in a browser
            - For Python: Create a functional script (no external dependencies unless requested)
            
            IMPORTANT:
            - If generating HTML/JavaScript, wrap it in a markdown code block: ```html
            YOUR HTML CODE HERE
            ```
            - If generating Python, wrap it in a markdown code block: ```python
            YOUR PYTHON CODE HERE
            ```
            - Respond ONLY with the code in a markdown code block. DO NOT include explanations, warnings, or additional text after the code block.
            
            User Request:
            $userPrompt
        """.trimIndent()
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            inferenceService.onCleared()
        }
    }
}
