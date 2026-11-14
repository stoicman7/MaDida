package com.madida

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val DeepRed = Color(0xFF950606)
private val PaleRed = Color(0xFFFFEEEE)
private val DarkText = Color(0xFF202020)

class MainActivity : ComponentActivity() {
    private lateinit var store: FamilyStore
    private lateinit var recorder: SimpleAudioRecorder
    private lateinit var player: PromptPlayer

    private var contacts by mutableStateOf<List<FamilyContact>>(emptyList())
    private var prompts by mutableStateOf(VoicePrompts())
    private var responses by mutableStateOf(VoiceResponses())
    private var screen by mutableStateOf(AppScreen.HOME)
    private var status by mutableStateOf("Prête")
    private var isRecording by mutableStateOf(false)
    private var recordingTask by mutableStateOf(RecordingTask.NONE)
    private var selectedContactId by mutableStateOf("")
    private var matchedContact by mutableStateOf<FamilyContact?>(null)

    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingPermissionFallback: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.isNotEmpty() && result.values.all { it }
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (allGranted) {
            pendingPermissionFallback = null
            action?.invoke()
        } else {
            val fallback = pendingPermissionFallback
            pendingPermissionFallback = null
            if (fallback != null) fallback.invoke()
            else status = "Autorisation nécessaire pour utiliser le microphone ou appeler."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FamilyStore(this)
        recorder = SimpleAudioRecorder()
        player = PromptPlayer()
        refreshData()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFFFFBFB)
                ) {
                    if (screen == AppScreen.HOME) {
                        HomeScreen(
                            status = status,
                            recording = isRecording,
                            matchedContact = matchedContact,
                            onMainAction = {
                                if (matchedContact == null) {
                                    if (isRecording && recordingTask == RecordingTask.CALL_NAME) stopCurrentRecording()
                                    else startRecording(RecordingTask.CALL_NAME)
                                } else {
                                    if (isRecording && recordingTask == RecordingTask.CONFIRMATION) stopCurrentRecording()
                                    else startRecording(RecordingTask.CONFIRMATION)
                                }
                            },
                            onYes = { matchedContact?.let { callContact(it) } },
                            onNo = {
                                matchedContact = null
                                status = "Prête"
                                playPrompt(prompts.askWho)
                            },
                            onOpenSetup = { screen = AppScreen.SETUP }
                        )
                    } else {
                        SetupScreen(
                            contacts = contacts,
                            prompts = prompts,
                            responses = responses,
                            recording = isRecording,
                            recordingTask = recordingTask,
                            selectedContactId = selectedContactId,
                            status = status,
                            onBack = { screen = AppScreen.HOME; playPrompt(prompts.askWho) },
                            onAddContact = { name, phone, aliases -> addContact(name, phone, aliases) },
                            onSelectContact = { selectedContactId = it },
                            onDeleteContact = { deleteContact(it) },
                            onDeleteContactSample = { contactId, audioPath -> deleteContactSample(contactId, audioPath) },
                            onDeleteContactAnnouncement = { deleteContactAnnouncement(it) },
                            onDeleteResponseSample = { kind, audioPath -> deleteResponseSample(kind, audioPath) },
                            onDeletePrompt = { deletePrompt(it) },
                            onRecordSample = {
                                selectedContactId = it
                                startRecording(RecordingTask.ENROLLMENT)
                            },
                            onTestRecognition = { startRecording(RecordingTask.TEST_NAME) },
                            onRecordAnnouncement = {
                                selectedContactId = it
                                startRecording(RecordingTask.CONTACT_LABEL)
                            },
                            onRecordPrompt = { key -> startRecording(RecordingTask.fromPromptKey(key)) },
                            onRecordResponse = { key -> startRecording(RecordingTask.fromResponseKey(key)) },
                            onStopRecording = { stopCurrentRecording() }
                        )
                    }
                }
            }
        }
        window.decorView.post { playPrompt(prompts.askWho) }
    }

    private fun refreshData() {
        contacts = store.loadContacts()
        prompts = store.loadPrompts()
        responses = store.loadResponses()
        if (selectedContactId.isBlank() && contacts.isNotEmpty()) selectedContactId = contacts.first().id
    }

    private fun deleteContact(contactId: String) {
        if (isRecording) {
            status = "Arrêtez d'abord l'enregistrement avant de supprimer un contact."
            return
        }

        val contact = contacts.firstOrNull { it.id == contactId } ?: return
        player.stop()

        val updatedContacts = contacts.filterNot { it.id == contactId }
        store.saveContacts(updatedContacts)
        contacts = updatedContacts

        if (selectedContactId == contactId) {
            selectedContactId = updatedContacts.firstOrNull()?.id.orEmpty()
        }
        if (matchedContact?.id == contactId) {
            matchedContact = null
        }

        (contact.samplePaths + listOfNotNull(contact.announcementPath))
            .distinct()
            .forEach { path ->
                val audioFile = File(path)
                LocalVoiceMatcher.forget(audioFile)
                deleteRecordingFile(audioFile)
            }

        status = "Contact ${contact.displayName} supprimé."
    }

    private fun deleteRecordingFile(file: File) {
        try {
            val recordingsDirectory = File(filesDir, "recordings").canonicalFile
            val candidate = file.canonicalFile
            if (candidate.parentFile == recordingsDirectory && candidate.isFile) {
                candidate.delete()
            }
        } catch (_: Exception) { }
    }

    private fun deleteContactSample(contactId: String, audioPath: String) {
        if (isRecording) return
        val contact = contacts.firstOrNull { it.id == contactId } ?: return
        if (audioPath !in contact.samplePaths) return

        val updated = contact.copy(samplePaths = contact.samplePaths.filterNot { it == audioPath })
        contacts = contacts.map { if (it.id == contactId) updated else it }
        store.saveContacts(contacts)
        removeAudioFile(audioPath)
        status = "Prononciation supprimée. Il reste ${updated.samplePaths.size} exemple(s)."
    }

    private fun deleteContactAnnouncement(contactId: String) {
        if (isRecording) return
        val contact = contacts.firstOrNull { it.id == contactId } ?: return
        val audioPath = contact.announcementPath ?: return
        val updated = contact.copy(announcementPath = null)
        contacts = contacts.map { if (it.id == contactId) updated else it }
        store.saveContacts(contacts)
        removeAudioFile(audioPath)
        status = "Enregistrement du nom à annoncer supprimé."
    }

    private fun deleteResponseSample(kind: String, audioPath: String) {
        if (isRecording) return
        responses = when (kind) {
            "yes" -> responses.copy(yesPaths = responses.yesPaths.filterNot { it == audioPath })
            else -> responses.copy(noPaths = responses.noPaths.filterNot { it == audioPath })
        }
        store.saveResponses(responses)
        removeAudioFile(audioPath)
        status = "Enregistrement vocal supprimé."
    }

    private fun deletePrompt(key: String) {
        if (isRecording) return
        val oldPath = when (key) {
            "askWho" -> prompts.askWho
            "confirm" -> prompts.confirm
            "repeat" -> prompts.repeat
            else -> prompts.notFound
        } ?: return

        player.stop()
        prompts = when (key) {
            "askWho" -> prompts.copy(askWho = null)
            "confirm" -> prompts.copy(confirm = null)
            "repeat" -> prompts.copy(repeat = null)
            else -> prompts.copy(notFound = null)
        }
        store.savePrompts(prompts)
        removeAudioFile(oldPath)
        status = "Audio d'instruction supprimé."
    }

    private fun removeAudioFile(path: String) {
        val file = File(path)
        player.stop()
        LocalVoiceMatcher.forget(file)
        deleteRecordingFile(file)
    }

    private fun addContact(name: String, phone: String, aliasesText: String) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        if (cleanName.isBlank() || cleanPhone.isBlank()) {
            status = "Le nom et le numéro sont obligatoires."
            return
        }
        val aliases = aliasesText.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val contact = FamilyContact(
            id = UUID.randomUUID().toString(),
            displayName = cleanName,
            phoneNumber = cleanPhone,
            aliases = aliases
        )
        store.saveContacts(contacts + contact)
        contacts = store.loadContacts()
        selectedContactId = contact.id
        status = "Contact ajouté. Enregistrez maintenant plusieurs prononciations."
    }

    private fun startRecording(task: RecordingTask) {
        val neededPermission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, neededPermission) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = { startRecording(task) }
            pendingPermissionFallback = null
            permissionLauncher.launch(arrayOf(neededPermission))
            return
        }
        if ((task == RecordingTask.ENROLLMENT || task == RecordingTask.CONTACT_LABEL) && selectedContactId.isBlank()) {
            status = "Ajoutez ou sélectionnez d'abord un contact."
            return
        }
        if (task == RecordingTask.CALL_NAME && contacts.isEmpty()) {
            status = "La famille doit d'abord ajouter des contacts."
            screen = AppScreen.SETUP
            return
        }
        try {
            player.stop()
            val folder = File(filesDir, "recordings").apply { mkdirs() }
            val file = File(folder, "${task.name.lowercase()}_${System.currentTimeMillis()}.wav")
            recorder.start(file) { message ->
                runOnUiThread {
                    if (isRecording && recordingTask == task) {
                        try { recorder.stop() } catch (_: Exception) { }
                        isRecording = false
                        recordingTask = RecordingTask.NONE
                        status = "L'enregistrement a été interrompu. Réessayez. ($message)"
                    }
                }
            }
            recordingTask = task
            isRecording = true
            status = when (task) {
                RecordingTask.CALL_NAME -> "Écoute… dites le prénom, puis touchez STOP."
                RecordingTask.TEST_NAME -> "Test : prononcez un nom, puis touchez STOP. Aucun appel ne sera lancé."
                RecordingTask.CONFIRMATION -> "Écoute… dites oui ou non, puis touchez STOP."
                RecordingTask.ENROLLMENT -> "La grand-mère prononce le nom, puis touchez STOP."
                RecordingTask.CONTACT_LABEL -> "Un membre de la famille prononce le nom, puis touchez STOP."
                RecordingTask.YES_ENROLLMENT -> "Prononcez votre réponse OUI, puis touchez STOP."
                RecordingTask.NO_ENROLLMENT -> "Prononcez votre réponse NON, puis touchez STOP."
                else -> "Enregistrement de la phrase… touchez STOP à la fin."
            }
        } catch (e: Exception) {
            status = "Impossible de démarrer le microphone : ${e.localizedMessage ?: "erreur"}"
            isRecording = false
            recordingTask = RecordingTask.NONE
        }
    }

    private fun stopCurrentRecording() {
        val task = recordingTask
        var stopError: String? = null
        val file = try { recorder.stop() } catch (e: Exception) {
            stopError = e.localizedMessage
            null
        }
        isRecording = false
        recordingTask = RecordingTask.NONE
        if (file == null || !file.exists() || file.length() < 1000) {
            file?.delete()
            status = stopError?.let { "Erreur d'enregistrement : $it" } ?: "Enregistrement trop court. Réessayez."
            return
        }
        when (task) {
            RecordingTask.CALL_NAME -> resolveSpokenContact(file)
            RecordingTask.TEST_NAME -> testSpokenContact(file)
            RecordingTask.CONFIRMATION -> resolveConfirmation(file)
            RecordingTask.ENROLLMENT -> saveEnrollment(file)
            RecordingTask.CONTACT_LABEL -> saveContactAnnouncement(file)
            RecordingTask.YES_ENROLLMENT -> saveResponseSample("yes", file)
            RecordingTask.NO_ENROLLMENT -> saveResponseSample("no", file)
            RecordingTask.PROMPT_ASK -> savePrompt("askWho", file)
            RecordingTask.PROMPT_CONFIRM -> savePrompt("confirm", file)
            RecordingTask.PROMPT_REPEAT -> savePrompt("repeat", file)
            RecordingTask.PROMPT_NOT_FOUND -> savePrompt("notFound", file)
            RecordingTask.NONE -> Unit
        }
    }

    private fun saveEnrollment(file: File) {
        val contact = contacts.firstOrNull { it.id == selectedContactId } ?: run {
            status = "Contact introuvable."
            return
        }
        val updated = contact.copy(samplePaths = contact.samplePaths + file.absolutePath)
        contacts = contacts.map { if (it.id == contact.id) updated else it }
        store.saveContacts(contacts)
        status = "Prononciation enregistrée (${updated.samplePaths.size}). Vous pouvez en ajouter d'autres."
    }

    private fun saveContactAnnouncement(file: File) {
        val contact = contacts.firstOrNull { it.id == selectedContactId } ?: run {
            status = "Contact introuvable."
            return
        }
        val updated = contact.copy(announcementPath = file.absolutePath)
        contacts = contacts.map { if (it.id == contact.id) updated else it }
        store.saveContacts(contacts)
        status = "Annonce du nom enregistrée. Elle sera jouée pendant la confirmation."
    }

    private fun savePrompt(key: String, file: File) {
        prompts = when (key) {
            "askWho" -> prompts.copy(askWho = file.absolutePath)
            "confirm" -> prompts.copy(confirm = file.absolutePath)
            "repeat" -> prompts.copy(repeat = file.absolutePath)
            else -> prompts.copy(notFound = file.absolutePath)
        }
        store.savePrompts(prompts)
        status = "Phrase vocale enregistrée."
    }

    private fun saveResponseSample(kind: String, file: File) {
        responses = when (kind) {
            "yes" -> responses.copy(yesPaths = responses.yesPaths + file.absolutePath)
            else -> responses.copy(noPaths = responses.noPaths + file.absolutePath)
        }
        store.saveResponses(responses)
        val count = if (kind == "yes") responses.yesPaths.size else responses.noPaths.size
        status = "Réponse ${if (kind == "yes") "OUI" else "NON"} enregistrée ($count). Vous pouvez répéter l'enregistrement."
    }

    private fun resolveSpokenContact(file: File) {
        lifecycleScope.launch {
            try {
                status = "Recherche locale du contact…"
                val decision = withContext(Dispatchers.Default) {
                    LocalVoiceMatcher.identify(file, contacts)
                }
                val contact = decision.contact
                if (decision.status == "match" && contact != null) {
                    matchedContact = contact
                    status = "J'ai reconnu ${contact.displayName}. Confirmez avec votre voix ou les boutons."
                    playConfirmationSequence(contact)
                } else {
                    matchedContact = null
                    status = when (decision.status) {
                        "need_enrollment" -> "Ajoutez d'abord un contact dans les réglages famille."
                        "need_calibration" -> "Enregistrez au moins trois prononciations par contact pour activer le garde-fou."
                        "unknown_sound" -> "Aucun contact ne correspond suffisamment à ce son. Répétez le nom."
                        else -> "Je n'ai pas pu comparer les prononciations. Réessayez."
                    }
                    playPrompt(prompts.repeat ?: prompts.notFound)
                }
            } catch (_: Exception) {
                matchedContact = null
                status = "Erreur pendant la reconnaissance. Vous pouvez réessayer sans relancer l'application."
                playPrompt(prompts.repeat ?: prompts.notFound)
            } finally {
                LocalVoiceMatcher.forget(file)
                file.delete()
            }
        }
    }

    private fun testSpokenContact(file: File) {
        lifecycleScope.launch {
            try {
                status = "Test de reconnaissance en cours… aucun appel ne sera lancé."
                val decision = withContext(Dispatchers.Default) {
                    LocalVoiceMatcher.identify(file, contacts)
                }
                val candidate = decision.contact ?: decision.bestCandidate
                status = when (decision.status) {
                    "match" -> "TEST : contact reconnu = ${candidate?.displayName}. Distance : ${"%.3f".format(java.util.Locale.US, decision.bestDistance ?: 0.0)}. Aucun appel lancé."
                    "unknown_sound" -> "TEST : son rejeté, aucun contact proposé. Aucun appel lancé."
                    "need_calibration" -> "TEST : il faut au moins trois exemples valides pour un contact."
                    "need_enrollment" -> "TEST : enregistrez au moins un contact."
                    else -> "TEST : enregistrement audio invalide. Réessayez."
                }
            } catch (_: Exception) {
                status = "Erreur pendant le test. Réessayez sans relancer l'application."
            } finally {
                LocalVoiceMatcher.forget(file)
                file.delete()
            }
        }
    }

    private fun resolveConfirmation(file: File) {
        val contact = matchedContact
        if (contact == null) {
            LocalVoiceMatcher.forget(file)
            file.delete()
            return
        }
        lifecycleScope.launch {
            try {
                status = "Vérification locale de votre réponse…"
                val decision = withContext(Dispatchers.Default) {
                    LocalVoiceMatcher.classifyYesNo(file, responses)
                }
                when (decision) {
                    "yes" -> callContact(contact)
                    "no" -> {
                        matchedContact = null
                        status = "D'accord. Qui voulez-vous appeler ?"
                        playPrompt(prompts.askWho)
                    }
                    else -> {
                        status = if (responses.yesPaths.size < 2 || responses.noPaths.size < 2) {
                            "Enregistrez plusieurs exemples de OUI et NON dans les réglages, ou utilisez les boutons OUI ou NON."
                        } else {
                            "Je n'ai pas reconnu votre réponse. Utilisez les boutons OUI ou NON."
                        }
                        playPrompt(prompts.repeat)
                    }
                }
            } catch (_: Exception) {
                status = "Erreur pendant la confirmation. Utilisez les boutons OUI ou NON."
            } finally {
                LocalVoiceMatcher.forget(file)
                file.delete()
            }
        }
    }

    private fun playConfirmationSequence(contact: FamilyContact) {
        val paths = mutableListOf<String>()
        prompts.confirm?.let { if (File(it).exists()) paths.add(it) }
        val nameAudio = contact.announcementPath
            ?: contact.samplePaths.lastOrNull()
        nameAudio?.let { if (File(it).exists()) paths.add(it) }
        if (paths.isNotEmpty()) player.playSequence(paths)
    }

    private fun playPrompt(path: String?) {
        if (path != null && File(path).exists()) player.playSequence(listOf(path))
    }

    private fun callContact(contact: FamilyContact) {
        val permission = Manifest.permission.CALL_PHONE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchPhoneCall(contact, direct = true)
        } else {
            pendingPermissionAction = { launchPhoneCall(contact, direct = true) }
            pendingPermissionFallback = { launchPhoneCall(contact, direct = false) }
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    private fun launchPhoneCall(contact: FamilyContact, direct: Boolean) {
        try {
            val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val cleanNumber = contact.phoneNumber.trim().replace(" ", "").replace("-", "")
            val intent = Intent(action, Uri.fromParts("tel", cleanNumber, null))
            startActivity(intent)
            status = if (direct) "Appel lancé vers ${contact.displayName}." else "Composeur téléphonique ouvert pour ${contact.displayName}."
            matchedContact = null
        } catch (e: Exception) {
            try {
                val cleanNumber = contact.phoneNumber.trim().replace(" ", "").replace("-", "")
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", cleanNumber, null)))
                status = "Le composeur téléphonique est ouvert."
            } catch (_: Exception) {
                status = "Impossible d'ouvrir l'application Téléphone."
            }
        }
    }

    override fun onPause() {
        if (::recorder.isInitialized && isRecording) {
            try {
                recorder.stop()?.let { it.delete() }
            } catch (_: Exception) { }
            isRecording = false
            recordingTask = RecordingTask.NONE
            status = "Enregistrement interrompu lorsque l'application a été quittée. Vous pouvez recommencer."
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.release()
        player.release()
    }
}

private enum class AppScreen { HOME, SETUP }
private enum class RecordingTask {
    NONE, CALL_NAME, TEST_NAME, CONFIRMATION, ENROLLMENT, CONTACT_LABEL, YES_ENROLLMENT, NO_ENROLLMENT, PROMPT_ASK, PROMPT_CONFIRM, PROMPT_REPEAT, PROMPT_NOT_FOUND;
    companion object {
        fun fromResponseKey(key: String): RecordingTask = if (key == "yes") YES_ENROLLMENT else NO_ENROLLMENT

        fun fromPromptKey(key: String): RecordingTask = when (key) {
            "askWho" -> PROMPT_ASK
            "confirm" -> PROMPT_CONFIRM
            "repeat" -> PROMPT_REPEAT
            else -> PROMPT_NOT_FOUND
        }
    }
}

@Composable
private fun HomeScreen(
    status: String,
    recording: Boolean,
    matchedContact: FamilyContact?,
    onMainAction: () -> Unit,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onOpenSetup: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("MaDida", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = DeepRed)
            TextButton(onClick = onOpenSetup) {
                Icon(Icons.Default.Settings, contentDescription = "Réglages famille", tint = DeepRed)
            }
        }
        if (matchedContact == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text("Qui voulez-vous appeler ?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = DarkText)
                Spacer(Modifier.height(28.dp))
                Box(
                    modifier = Modifier.size(190.dp).background(if (recording) Color(0xFFBDBDBD) else DeepRed, CircleShape)
                        .clickable(onClick = onMainAction),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = if (recording) "Arrêter" else "Parler", tint = Color.White, modifier = Modifier.size(86.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(if (recording) "TOUCHEZ POUR TERMINER" else "TOUCHEZ POUR PARLER", fontWeight = FontWeight.Bold, color = DeepRed, fontSize = 16.sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text("Voulez-vous appeler ?", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.size(132.dp).background(PaleRed, CircleShape), contentAlignment = Alignment.Center) {
                    Text(matchedContact.displayName.take(1).uppercase(), fontSize = 52.sp, color = DeepRed, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(matchedContact.displayName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DarkText)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onYes, modifier = Modifier.weight(1f).height(76.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF207A38)), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("OUI", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = onNo, modifier = Modifier.weight(1f).height(76.dp), colors = ButtonDefaults.buttonColors(containerColor = DeepRed), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("NON", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Button(onClick = onMainAction, modifier = Modifier.fillMaxWidth().height(72.dp), colors = ButtonDefaults.buttonColors(containerColor = DeepRed), shape = RoundedCornerShape(18.dp)) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (recording) "ARRÊTER" else "RÉPONDRE À LA VOIX", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
        Text(status, fontSize = 14.sp, color = Color(0xFF555555), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    contacts: List<FamilyContact>,
    prompts: VoicePrompts,
    responses: VoiceResponses,
    recording: Boolean,
    recordingTask: RecordingTask,
    selectedContactId: String,
    status: String,
    onBack: () -> Unit,
    onAddContact: (String, String, String) -> Unit,
    onSelectContact: (String) -> Unit,
    onDeleteContact: (String) -> Unit,
    onDeleteContactSample: (String, String) -> Unit,
    onDeleteContactAnnouncement: (String) -> Unit,
    onDeleteResponseSample: (String, String) -> Unit,
    onDeletePrompt: (String) -> Unit,
    onRecordSample: (String) -> Unit,
    onTestRecognition: () -> Unit,
    onRecordAnnouncement: (String) -> Unit,
    onRecordPrompt: (String) -> Unit,
    onRecordResponse: (String) -> Unit,
    onStopRecording: () -> Unit
) {
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var phone by androidx.compose.runtime.remember { mutableStateOf("") }
    var aliases by androidx.compose.runtime.remember { mutableStateOf("") }
    var contactToDelete by androidx.compose.runtime.remember { mutableStateOf<FamilyContact?>(null) }
    var audioToDelete by androidx.compose.runtime.remember { mutableStateOf<PendingAudioDeletion?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Configuration MaDida", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = DeepRed)
                TextButton(onClick = onBack) { Text("Retour", color = DeepRed) }
            }
            Text("Cet écran est destiné à la famille. La grand-mère n'a pas besoin de le consulter.", color = Color.DarkGray, fontSize = 13.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ajouter une personne", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nom affiché") }, singleLine = true)
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Numéro de téléphone") }, singleLine = true)
                    OutlinedTextField(value = aliases, onValueChange = { aliases = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Autres surnoms (enregistrez aussi chaque surnom comme prononciation)") })
                    Button(onClick = { onAddContact(name, phone, aliases); name = ""; phone = ""; aliases = "" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = DeepRed)) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ajouter le contact")
                    }
                }
            }
        }
        item {
            Text("Enregistrer les prononciations", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkText)
            Text("Pour activer le garde-fou contre les sons inconnus, enregistrez au moins trois prononciations par contact. Cinq à huit sont préférables.", color = Color.DarkGray, fontSize = 13.sp)
            val testingNow = recording && recordingTask == RecordingTask.TEST_NAME
            OutlinedButton(
                onClick = { if (testingNow) onStopRecording() else onTestRecognition() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !recording || testingNow && contacts.any { it.samplePaths.size >= 3 }
            ) {
                Icon(if (testingNow) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (testingNow) "Arrêter le test" else "Tester la reconnaissance (sans appeler)")
            }
        }
        items(contacts, key = { it.id }) { contact ->
            val selected = selectedContactId == contact.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelectContact(contact.id) }.border(if (selected) 2.dp else 1.dp, if (selected) DeepRed else Color.LightGray, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = if (selected) PaleRed else Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(contact.displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(contact.phoneNumber, color = Color.DarkGray, fontSize = 13.sp)
                            Text("Exemples enregistrés : ${contact.samplePaths.size}", color = Color.DarkGray, fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { contactToDelete = contact },
                            enabled = !recording
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Supprimer ${contact.displayName}",
                                tint = DeepRed
                            )
                        }
                    }
                    contact.samplePaths.forEachIndexed { index, audioPath ->
                        AudioSampleRow(
                            label = "Prononciation ${index + 1}",
                            onDelete = {
                                audioToDelete = PendingAudioDeletion("la prononciation ${index + 1} de ${contact.displayName}") {
                                    onDeleteContactSample(contact.id, audioPath)
                                }
                            },
                            enabled = !recording
                        )
                    }
                    val recordingSample = recording &&
                        recordingTask == RecordingTask.ENROLLMENT &&
                        selectedContactId == contact.id
                    OutlinedButton(
                        onClick = { if (recordingSample) onStopRecording() else onRecordSample(contact.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !recording || recordingSample
                    ) {
                        Icon(if (recordingSample) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recordingSample) "Arrêter" else "Ajouter une prononciation")
                    }
                    if (contact.announcementPath != null) {
                        AudioSampleRow(
                            label = "Audio du nom à annoncer",
                            onDelete = {
                                audioToDelete = PendingAudioDeletion("l'audio d'annonce de ${contact.displayName}") {
                                    onDeleteContactAnnouncement(contact.id)
                                }
                            },
                            enabled = !recording
                        )
                    } else {
                        Text("Nom à annoncer : non enregistré", color = Color.DarkGray, fontSize = 12.sp)
                    }
                    val recordingAnnouncement = recording &&
                        recordingTask == RecordingTask.CONTACT_LABEL &&
                        selectedContactId == contact.id
                    OutlinedButton(
                        onClick = { if (recordingAnnouncement) onStopRecording() else onRecordAnnouncement(contact.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !recording || recordingAnnouncement
                    ) {
                        Icon(if (recordingAnnouncement) Icons.Default.Stop else Icons.Default.Call, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recordingAnnouncement) "Arrêter" else "Enregistrer le nom à annoncer")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Réponses vocales de votre grand-mère", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Pour confirmer un appel sans toucher l'écran, enregistrez au moins deux façons naturelles de dire OUI et deux de dire NON, dans sa variante de kabyle.", color = Color.DarkGray, fontSize = 13.sp)
                    Text("OUI : ${responses.yesPaths.size} exemples  •  NON : ${responses.noPaths.size} exemples", color = DeepRed, fontWeight = FontWeight.SemiBold)
                    responses.yesPaths.forEachIndexed { index, audioPath ->
                        AudioSampleRow(
                            label = "OUI — exemple ${index + 1}",
                            onDelete = {
                                audioToDelete = PendingAudioDeletion("cet exemple de réponse OUI") {
                                    onDeleteResponseSample("yes", audioPath)
                                }
                            },
                            enabled = !recording
                        )
                    }
                    val recordingYes = recording && recordingTask == RecordingTask.YES_ENROLLMENT
                    OutlinedButton(
                        onClick = { if (recordingYes) onStopRecording() else onRecordResponse("yes") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !recording || recordingYes
                    ) {
                        Icon(if (recordingYes) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recordingYes) "Arrêter" else "Enregistrer un OUI")
                    }
                    responses.noPaths.forEachIndexed { index, audioPath ->
                        AudioSampleRow(
                            label = "NON — exemple ${index + 1}",
                            onDelete = {
                                audioToDelete = PendingAudioDeletion("cet exemple de réponse NON") {
                                    onDeleteResponseSample("no", audioPath)
                                }
                            },
                            enabled = !recording
                        )
                    }
                    val recordingNo = recording && recordingTask == RecordingTask.NO_ENROLLMENT
                    OutlinedButton(
                        onClick = { if (recordingNo) onStopRecording() else onRecordResponse("no") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !recording || recordingNo
                    ) {
                        Icon(if (recordingNo) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recordingNo) "Arrêter" else "Enregistrer un NON")
                    }
                }
            }
        }
        item {
            Text("Enregistrer les phrases en kabyle", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkText)
            Text("Un membre de la famille peut enregistrer ces phrases dans la variante de Béjaïa. L'application les fera écouter à votre grand-mère.", color = Color.DarkGray, fontSize = 13.sp)
        }
        item {
            PromptRecordRow(
                "Demander qui appeler", "askWho", prompts.askWho, recording, recordingTask,
                onRecordPrompt, onStopRecording,
                onDelete = { audioToDelete = PendingAudioDeletion("l'audio « Demander qui appeler »") { onDeletePrompt("askWho") } }
            )
        }
        item {
            PromptRecordRow(
                "Demander confirmation", "confirm", prompts.confirm, recording, recordingTask,
                onRecordPrompt, onStopRecording,
                onDelete = { audioToDelete = PendingAudioDeletion("l'audio « Demander confirmation »") { onDeletePrompt("confirm") } }
            )
        }
        item {
            PromptRecordRow(
                "Demander de répéter", "repeat", prompts.repeat, recording, recordingTask,
                onRecordPrompt, onStopRecording,
                onDelete = { audioToDelete = PendingAudioDeletion("l'audio « Demander de répéter »") { onDeletePrompt("repeat") } }
            )
        }
        item {
            PromptRecordRow(
                "Nom non reconnu", "notFound", prompts.notFound, recording, recordingTask,
                onRecordPrompt, onStopRecording,
                onDelete = { audioToDelete = PendingAudioDeletion("l'audio « Nom non reconnu »") { onDeletePrompt("notFound") } }
            )
        }
        item {
            Text(status, color = DeepRed, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }

    audioToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { audioToDelete = null },
            title = { Text("Supprimer cet audio ?") },
            text = { Text("Voulez-vous supprimer définitivement ${target.label} ? Le contact ou la fonction associée sera conservé.") },
            confirmButton = {
                TextButton(onClick = {
                    target.onDelete()
                    audioToDelete = null
                }) { Text("Supprimer", color = DeepRed) }
            },
            dismissButton = {
                TextButton(onClick = { audioToDelete = null }) { Text("Annuler") }
            }
        )
    }

    contactToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("Supprimer ce contact ?") },
            text = {
                Text("Le contact ${target.displayName} et ses enregistrements vocaux seront supprimés définitivement.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteContact(target.id)
                        contactToDelete = null
                    }
                ) {
                    Text("Supprimer", color = DeepRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }
}

private data class PendingAudioDeletion(
    val label: String,
    val onDelete: () -> Unit
)

@Composable
private fun AudioSampleRow(
    label: String,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray, fontSize = 13.sp)
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer $label", tint = DeepRed)
        }
    }
}

@Composable
private fun PromptRecordRow(
    title: String,
    key: String,
    path: String?,
    recording: Boolean,
    recordingTask: RecordingTask,
    onRecord: (String) -> Unit,
    onStop: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(if (path != null) "Enregistrée" else "Pas encore enregistrée", color = Color.Gray, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { if (recording && recordingTask == RecordingTask.fromPromptKey(key)) onStop() else if (!recording) onRecord(key) },
                    enabled = !recording || recordingTask == RecordingTask.fromPromptKey(key)
                ) {
                    Text(if (recording && recordingTask == RecordingTask.fromPromptKey(key)) "STOP" else "Enregistrer")
                }
                if (path != null && onDelete != null) {
                    IconButton(onClick = onDelete, enabled = !recording) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer l'audio", tint = DeepRed)
                    }
                }
            }
        }
    }
}
