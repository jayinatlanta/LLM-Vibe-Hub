package com.llmhub.llmhub.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.CreatorEntity
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreatorViewModel(
    private val repository: ChatRepository,
    private val inferenceService: InferenceService,
    private val context: Context
) : ViewModel() {

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generatedCreator = MutableStateFlow<CreatorEntity?>(null)
    val generatedCreator: StateFlow<CreatorEntity?> = _generatedCreator.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun generateCreator(userPrompt: String) {
        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _generatedCreator.value = null

            try {
                val model = inferenceService.getCurrentlyLoadedModel()
                if (model == null) {
                    _error.value = "No model loaded. Please load a model in Chat first."
                    _isGenerating.value = false
                    return@launch
                }

                val metaPrompt = """
                    You are an expert AI persona creator. Your goal is to create a detailed system prompt for a new AI agent based on the user's description.
                    
                    User Description: "$userPrompt"
                    
                    Structure your response EXACTLY in this format (PCTF):
                    
                    NAME: [A creative name for the agent]
                    ICON: [A single emoji representing the agent]
                    DESCRIPTION: [A short 1-sentence description]
                    SYSTEM_PROMPT:
                    [The detailed system prompt for the agent to follow. Use "You are..." phrasing. Include Personality, Context, Task, and Format instructions.]
                    
                    Do not add any other text or conversational filler. Just the format above.
                """.trimIndent()

                val response = inferenceService.generateResponse(metaPrompt, model)
                
                val parsedCreator = parseResponse(response, userPrompt)
                if (parsedCreator != null) {
                    _generatedCreator.value = parsedCreator
                } else {
                    _error.value = "Failed to parse generation result. Try again."
                }

            } catch (e: Exception) {
                Log.e("CreatorViewModel", "Generation failed", e)
                _error.value = "Error: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun parseResponse(response: String, originalPrompt: String): CreatorEntity? {
        try {
            val nameRegex = Regex("NAME:\\s*(.+)")
            val iconRegex = Regex("ICON:\\s*(.+)")
            val descriptionRegex = Regex("DESCRIPTION:\\s*(.+)")
            val promptRegex = Regex("SYSTEM_PROMPT:\\s*([\\s\\S]*)")

            val name = nameRegex.find(response)?.groupValues?.get(1)?.trim() ?: "My Creator"
            val icon = iconRegex.find(response)?.groupValues?.get(1)?.trim()?.take(2) ?: "🤖" // Fallback to robot if parse fails or text is too long
            val description = descriptionRegex.find(response)?.groupValues?.get(1)?.trim() ?: originalPrompt
            val systemPrompt = promptRegex.find(response)?.groupValues?.get(1)?.trim() ?: response

            return CreatorEntity(
                name = name,
                icon = icon,
                description = description,
                pctfPrompt = systemPrompt
            )
        } catch (e: Exception) {
            Log.e("CreatorViewModel", "Parsing failed", e)
            return null
        }
    }

    fun saveCreator(creator: CreatorEntity, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.insertCreator(creator)
            onSaved()
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}
