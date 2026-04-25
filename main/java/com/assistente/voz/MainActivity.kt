package com.assistente.voz

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

// ─────────────────────────────────────────────
//  Data class para representar um contato
// ─────────────────────────────────────────────
data class Contato(val nome: String, val numero: String)

// ─────────────────────────────────────────────
//  Estados da aplicação
// ─────────────────────────────────────────────
enum class Estado {
    IDLE,                  // aguardando toque do usuário
    OUVINDO,               // microfone ativo, reconhecendo voz
    AGUARDANDO_CONFIRMACAO // perguntou algo, esperando sim/não
}

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── UI ──────────────────────────────────
    private lateinit var btnFalar: Button
    private lateinit var btnWhatsApp: Button
    private lateinit var btnInternet: Button
    private lateinit var btnLigar: Button
    private lateinit var layoutConfirmacao: LinearLayout
    private lateinit var btnSim: Button
    private lateinit var btnNao: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvConfirmacaoTexto: TextView

    // ── Voz ─────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val ttsCallbacks = mutableMapOf<String, () -> Unit>()

    // ── Estado ──────────────────────────────
    private var estado = Estado.IDLE
    private var acaoPendente: (() -> Unit)? = null   // executa após "sim"

    // ── Contatos encontrados na última busca ─
    private var contatosEncontrados: List<Contato> = emptyList()

    // ── Permissões necessárias ───────────────
    private val PERMISSOES = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    )
    private val COD_PERMISSAO = 101

    // ─────────────────────────────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vincularViews()
        configurarBotoes()

        // Inicializa TTS; o microfone só é criado depois que TTS estiver pronto
        tts = TextToSpeech(this, this)

        pedirPermissoes()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == COD_PERMISSAO) {
            val negadas = permissions.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
                .map { permissions[it] }
            
            if (negadas.isEmpty()) {
                setStatus("Permissões concedidas!")
                falar("Permissões concedidas. Já podemos começar.")
            } else {
                setStatus("Algumas permissões foram negadas.")
                Log.w("PERM", "Negadas: $negadas")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer?.destroy()
    }

    // ─────────────────────────────────────────────────────────────────
    //  VIEWS
    // ─────────────────────────────────────────────────────────────────

    private fun vincularViews() {
        btnFalar             = findViewById(R.id.btnFalar)
        btnWhatsApp          = findViewById(R.id.btnWhatsApp)
        btnInternet          = findViewById(R.id.btnInternet)
        btnLigar             = findViewById(R.id.btnLigar)
        layoutConfirmacao    = findViewById(R.id.layoutConfirmacao)
        btnSim               = findViewById(R.id.btnSim)
        btnNao               = findViewById(R.id.btnNao)
        tvStatus             = findViewById(R.id.tvStatus)
        tvConfirmacaoTexto   = findViewById(R.id.tvConfirmacaoTexto)
    }

    private fun configurarBotoes() {
        btnFalar.setOnClickListener    { aoTocárFalar() }
        btnWhatsApp.setOnClickListener { falarDepoisExecutar("Abrindo WhatsApp") { abrirWhatsApp() } }
        btnInternet.setOnClickListener { falarDepoisExecutar("Abrindo a internet") { abrirInternet() } }
        btnLigar.setOnClickListener    { aoTocárLigar() }
        btnSim.setOnClickListener      { confirmarAcao() }
        btnNao.setOnClickListener      { cancelarAcao() }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PERMISSÕES
    // ─────────────────────────────────────────────────────────────────

    private fun pedirPermissoes() {
        val faltam = PERMISSOES.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltam.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltam.toTypedArray(), COD_PERMISSAO)
        }
    }

    private fun temPermissao(permissao: String) =
        ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────
    //  TEXT-TO-SPEECH (TTS)
    // ─────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        runOnUiThread {
            if (status == TextToSpeech.SUCCESS) {
                val resultado = tts.setLanguage(Locale("pt", "BR"))
                ttsReady = resultado != TextToSpeech.LANG_MISSING_DATA &&
                        resultado != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsReady) {
                    tts.setSpeechRate(0.85f)   // mais devagar para idosos
                    
                    // Configura o listener de progresso do TTS uma única vez
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onError(id: String?) {
                            id?.let { ttsCallbacks.remove(it) }
                        }
                        override fun onDone(id: String?) {
                            id?.let {
                                val callback = ttsCallbacks.remove(it)
                                if (callback != null) {
                                    runOnUiThread { callback.invoke() }
                                }
                            }
                        }
                    })

                    inicializarSpeechRecognizer()
                    // Saudação inicial após 800ms
                    Handler(Looper.getMainLooper()).postDelayed({
                        falar("Olá! Toque no botão amarelo e fale o que precisa.")
                        setStatus("Toque em FALAR e diga o que precisa")
                    }, 800)
                } else {
                    setStatus("Erro: voz em português não disponível")
                }
            }
        }
    }

    /**
     * Fala o [texto] e, quando terminar, executa [aoTerminar] (opcional).
     * Usa um utteranceId único para rastrear o fim da fala.
     */
    private fun falar(texto: String, aoTerminar: (() -> Unit)? = null) {
        if (!ttsReady) {
            aoTerminar?.invoke()
            return
        }
        Log.d("TTS", "Falando: $texto")

        val utteranceId = "utt_${System.currentTimeMillis()}"
        if (aoTerminar != null) {
            ttsCallbacks[utteranceId] = aoTerminar
        }

        val params = Bundle()
        val result = tts.speak(texto, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
            Log.e("TTS", "Erro ao disparar fala")
            ttsCallbacks.remove(utteranceId)
            aoTerminar?.invoke()
        }
    }

    /** Fala, exibe status, e executa ação ao terminar (com delay mínimo). */
    private fun falarDepoisExecutar(msg: String, delay: Long = 1200, acao: () -> Unit) {
        setStatus("$msg...")
        falar(msg) {
            Handler(Looper.getMainLooper()).postDelayed({ acao() }, delay)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  SPEECH RECOGNIZER
    // ─────────────────────────────────────────────────────────────────

    private fun inicializarSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Reconhecimento de voz não disponível neste aparelho")
            return
        }
        
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val texto = matches?.firstOrNull()?.lowercase(Locale("pt", "BR")) ?: ""
                Log.d("STT", "Resultado: '$texto'")
                pararEscuta()
                if (texto.isBlank()) {
                    erroReconhecimento()
                } else {
                    processarComando(texto)
                }
            }

            override fun onError(error: Int) {
                Log.d("STT", "Erro: $error")
                pararEscuta()
                val mensagemErro = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi o que você disse."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Você demorou para falar."
                    SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone."
                    SpeechRecognizer.ERROR_NETWORK -> "Erro de conexão com a internet."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "A internet está lenta."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O sistema está ocupado."
                    else -> "Ocorreu um problema ($error)."
                }
                
                setStatus(mensagemErro)
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    erroReconhecimento()
                }
            }

            // Callbacks obrigatórios (não precisamos usar todos)
            override fun onReadyForSpeech(params: Bundle?)      { 
                setStatus("🎙️ PODE FALAR AGORA") 
                tvStatus.setBackgroundResource(R.drawable.bg_status_listening)
                try {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.let {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            it.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            it.vibrate(100)
                        }
                    }
                } catch (e: Exception) {}
            }
            override fun onBeginningOfSpeech()                  {
                setStatus("🎙️ ESTOU OUVINDO...")
            }
            override fun onRmsChanged(rmsdB: Float)             {}
            override fun onBufferReceived(buffer: ByteArray?)   {}
            override fun onEndOfSpeech()                        { 
                setStatus("⌛ PROCESSANDO...") 
                tvStatus.setBackgroundResource(R.drawable.bg_status)
            }
            override fun onPartialResults(partial: Bundle?)     {}
            override fun onEvent(type: Int, params: Bundle?)    {}
        })
    }

    private fun iniciarEscuta() {
        if (!temPermissao(Manifest.permission.RECORD_AUDIO)) {
            pedirPermissoes()
            return
        }
        
        // Garante que o estado visual mude antes de tentar ligar o mic
        setStatus("🎙️ PREPARANDO...")
        btnFalar.isEnabled = false

        // Configuração ultra-simples para máxima compatibilidade
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale agora...")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        try {
            // Reinicia o recognizer para limpar qualquer erro anterior
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    setStatus("🎙️ PODE FALAR AGORA")
                    tvStatus.setBackgroundResource(R.drawable.bg_status_listening)
                }
                override fun onBeginningOfSpeech() { setStatus("🎙️ OUVINDO...") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setStatus("⌛ PROCESSANDO...") }
                
                override fun onError(error: Int) {
                    Log.e("STT", "Erro detectado: $error")
                    android.widget.Toast.makeText(this@MainActivity, "Erro de voz: $error", android.widget.Toast.LENGTH_SHORT).show()
                    pararEscuta()
                    
                    val msg = when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Não ouvi nada. Tente de novo."
                        SpeechRecognizer.ERROR_NETWORK -> "Verifique sua internet."
                        SpeechRecognizer.ERROR_AUDIO -> "Problema no microfone."
                        else -> "Tente novamente ($error)"
                    }
                    setStatus(msg)
                    falar(msg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val texto = matches?.firstOrNull() ?: ""
                    pararEscuta()
                    if (texto.isNotEmpty()) {
                        processarComando(texto)
                    } else {
                        erroReconhecimento()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("STT", "Falha fatal: ${e.message}")
            pararEscuta()
        }
    }

    private fun pararEscuta() {
        // Se estávamos aguardando confirmação, mantemos o estado interno, mas liberamos a UI
        if (estado != Estado.AGUARDANDO_CONFIRMACAO) {
            estado = Estado.IDLE
        }
        btnFalar.isEnabled = true
        tvStatus.setBackgroundResource(R.drawable.bg_status)
    }

    // ─────────────────────────────────────────────────────────────────
    //  AÇÕES DOS BOTÕES PRINCIPAIS
    // ─────────────────────────────────────────────────────────────────

    private fun aoTocárFalar() {
        // Se o assistente estiver falando, para a fala imediatamente para ouvir o usuário
        if (tts.isSpeaking) {
            tts.stop()
            ttsCallbacks.clear()
        }

        if (estado == Estado.OUVINDO) {
            speechRecognizer?.stopListening()
            return
        }
        
        // Só esconde confirmação se NÃO estivermos esperando uma
        if (estado != Estado.AGUARDANDO_CONFIRMACAO) {
            esconderConfirmacao()
            contatosEncontrados = emptyList()
        }
        
        setStatus("🎙️ PREPARANDO...")
        // Tenta iniciar direto se o usuário apertar o botão central, para ser mais rápido
        iniciarEscuta()
    }

    private fun aoTocárLigar() {
        if (tts.isSpeaking) {
            tts.stop()
            ttsCallbacks.clear()
        }

        if (estado == Estado.OUVINDO) return
        esconderConfirmacao()
        setStatus("Para quem quer ligar?")
        falar("Para quem você quer ligar?") {
            Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 400)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PROCESSAMENTO DE COMANDOS
    // ─────────────────────────────────────────────────────────────────

    private fun processarComando(texto: String) {
        Log.d("CMD", "Bruto: '$texto'")
        
        // Limpa estado anterior antes de processar novo comando para evitar "memória" de ligações passadas
        if (estado != Estado.AGUARDANDO_CONFIRMACAO) {
            acaoPendente = null
            // Não limpamos contatosEncontrados aqui pois o próximo passo pode precisar deles
        }
        
        // Remove pontuação e limpa espaços extras
        val textoLimpo = texto.replace(Regex("[.\\-,!?]"), "").trim()
        
        // Exibe o que ouviu para o usuário ver
        android.widget.Toast.makeText(this, "Ouvi: $textoLimpo", android.widget.Toast.LENGTH_LONG).show()

        // Normaliza variações coloquiais e erros comuns de reconhecimento
        var normalizado = textoLimpo.lowercase(Locale("pt", "BR"))
            .replace("zap zap", "whatsapp")
            .replace("zap", "whatsapp")
            .replace("wats", "whatsapp")
            .replace("whats", "whatsapp")
            .replace("uats", "whatsapp")
            .replace("vats", "whatsapp")
            .replace("mandar uma", "abrir")
            .replace("manda uma", "abrir")
            .replace("mandar", "abrir")
            .replace("manda", "abrir")
            .replace("envia", "abrir")
            .replace("enviar", "abrir")
            .replace("conversa com", "abrir")
            .replace("conversa no", "abrir")
            .replace("navegador", "internet")
            .replace("google", "internet")
            .replace("site", "internet")
            .replace("browser", "internet")
            .replace("ligue", "ligar")
            .replace("chame", "ligar")
            .replace("chamar", "ligar")
            .replace("telefone", "ligar")

        Log.d("CMD", "Normalizado: '$normalizado'")

        when {
            // ── Confirmação (aguardando sim/não) ────────────
            estado == Estado.AGUARDANDO_CONFIRMACAO -> {
                processarConfirmacao(normalizado)
            }

            // ── WhatsApp (Abrir conversa) ────────────────────────
            "whatsapp" in normalizado || "mensagem" in normalizado || "abrir" in normalizado -> {
                // Se a frase contém whatsapp ou "mensagem" ou foi traduzida para "abrir"
                val nomeBuscado = extrairNomeDaFrase(normalizado)
                if (nomeBuscado.isNullOrBlank() && "whatsapp" in normalizado) {
                    falarDepoisExecutar("Abrindo WhatsApp") { abrirWhatsApp() }
                } else if (!nomeBuscado.isNullOrBlank()) {
                    buscarEAbrirConversa(nomeBuscado)
                } else {
                    erroReconhecimento()
                }
            }

            // ── Internet ────────────────────────────────────
            "internet" in normalizado || "navegador" in normalizado || "site" in normalizado -> {
                falarDepoisExecutar("Abrindo a internet") { abrirInternet() }
            }

            // ── Ligar ──────────────────────────────────────
            "ligar" in normalizado || "contato" in normalizado || "telefone" in normalizado -> {
                val nomeBuscado = extrairNomeDaFrase(normalizado)
                if (nomeBuscado.isNullOrBlank()) {
                    setStatus("Para quem quer ligar?")
                    falar("Para quem você quer ligar? Diga o nome.") {
                        Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 300)
                    }
                } else {
                    buscarELigarParaContato(nomeBuscado)
                }
            }

            // ── Nome solto (possível resposta a "para quem ligar?") ─
            normalizado.length > 2 -> {
                // Tenta buscar como nome de contato
                buscarELigarParaContato(normalizado)
            }

            // ── Não entendeu ─────────────────────────────────
            else -> erroReconhecimento()
        }
    }

    /**
     * Extrai o nome do contato da frase.
     */
    private fun extrairNomeDaFrase(texto: String): String? {
        val frase = texto.lowercase(Locale("pt", "BR"))
        
        // Prioriza marcadores mais longos
        val marcadores = listOf(
            "ligar para o ", "ligar para a ", "ligar para ", 
            "ligue para o ", "ligue para a ", "ligue para ",
            "chamar o ", "chamar a ", "chamar ",
            "ligar pro ", "ligar pra ", "ligar ", "ligue "
        )
        
        for (marcador in marcadores) {
            if (frase.contains(marcador)) {
                val nome = frase.substring(frase.indexOf(marcador) + marcador.length).trim()
                if (nome.isNotBlank()) return nome
            }
        }
        
        return if (frase.isNotBlank() && frase.length > 2) frase else null
    }

    // ─────────────────────────────────────────────────────────────────
    //  CONTATOS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Busca contatos cujo nome contenha [nome] (busca parcial, insensível a acento/maiúscula).
     */
    private fun buscarContatos(nomeBuscado: String): List<Contato> {
        if (!temPermissao(Manifest.permission.READ_CONTACTS)) {
            pedirPermissoes()
            return emptyList()
        }

        val nomeAlvo = normalizarTextoParaComparacao(nomeBuscado)
        val resultado = mutableListOf<Contato>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projecao = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(uri, projecao, null, null, null)?.use { cursor ->
            val colNome = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val colNum  = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val n = cursor.getString(colNome) ?: continue
                val nNorm = normalizarTextoParaComparacao(n)
                
                // Verifica se o nome alvo está contido no nome do contato ou vice-versa
                if (nNorm.contains(nomeAlvo) || nomeAlvo.contains(nNorm)) {
                    val num = cursor.getString(colNum)?.replace(Regex("[^\\d+]"), "") ?: continue
                    if (resultado.none { it.nome == n }) {   // evita duplicatas
                        resultado.add(Contato(n, num))
                    }
                }
            }
        }
        
        // Ordena para que os nomes mais curtos (mais próximos da busca) venham primeiro
        return resultado.sortedBy { it.nome.length }
    }

    private fun buscarELigarParaContato(nomeBuscado: String) {
        setStatus("Buscando $nomeBuscado...")
        val encontrados = buscarContatos(nomeBuscado)
        contatosEncontrados = encontrados
        processarEscolhaContato(nomeBuscado, encontrados, false)
    }

    private fun buscarEAbrirConversa(nome: String) {
        val encontrados = buscarContatos(nome)
        contatosEncontrados = encontrados
        processarEscolhaContato(nome, encontrados, true)
    }

    private var isAcaoWhatsAppPendente = false

    private fun processarEscolhaContato(nomeBuscado: String, encontrados: List<Contato>, isZap: Boolean) {
        isAcaoWhatsAppPendente = isZap
        val nomeAlvoNorm = normalizarTextoParaComparacao(nomeBuscado)

        when {
            encontrados.isEmpty() -> {
                setStatus("Contato não encontrado.")
                falar("Não encontrei ninguém chamado $nomeBuscado nos seus contatos.")
            }
            encontrados.size == 1 -> {
                val contato = encontrados[0]
                val contatoNorm = normalizarTextoParaComparacao(contato.nome)
                
                if (contatoNorm == nomeAlvoNorm) {
                    // Match perfeito em som
                    if (isZap) {
                        falarDepoisExecutar("Abrindo WhatsApp para ${contato.nome}") { executarLigacaoWhatsApp(contato) }
                    } else {
                        pedirConfirmacaoLigacao(contato)
                    }
                } else {
                    // Match parcial (ex: "Lucas" para "Lucas Florencio")
                    val msg = "Encontrei ${contato.nome}. Você quer ligar, abrir o WhatsApp ou deseja cancelar?"
                    setStatus(msg)
                    contatosEncontrados = listOf(contato)
                    estado = Estado.AGUARDANDO_CONFIRMACAO
                    mostrarOverlayConfirmacao(msg)
                    falar(msg) { Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 400) }
                }
            }
            else -> {
                // Se houver mais de um, verifica se UM deles é o match perfeito em som
                val matchesExatos = encontrados.filter { normalizarTextoParaComparacao(it.nome) == nomeAlvoNorm }
                
                if (matchesExatos.size == 1) {
                    val exato = matchesExatos[0]
                    pedirConfirmacaoLigacao(exato)
                    return
                }

                // Múltiplos resultados: Pergunta primeiro QUAL contato
                val listaNomes = encontrados.map { it.nome }
                val nomesFormatados = if (listaNomes.size > 1) {
                    listaNomes.dropLast(1).joinToString(", ") + " ou " + listaNomes.last()
                } else {
                    listaNomes.first()
                }
                
                val msg = "Encontrei esses nomes: $nomesFormatados. Qual deles você quer?"

                setStatus(msg)
                contatosEncontrados = encontrados
                estado = Estado.AGUARDANDO_CONFIRMACAO
                mostrarOverlayConfirmacao(msg)
                falar(msg) { Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 400) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  CONFIRMAÇÃO
    // ─────────────────────────────────────────────────────────────────

    private fun pedirConfirmacaoLigacao(contato: Contato) {
        val pergunta = "Você quer ligar para ${contato.nome}, abrir o WhatsApp ou deseja cancelar?"
        estado = Estado.AGUARDANDO_CONFIRMACAO

        setStatus(pergunta)
        mostrarOverlayConfirmacao(pergunta)
        
        falar(pergunta) {
            Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 300)
        }
        
        // Define os contatos encontrados apenas com este contato para o processarConfirmacao saber quem é
        contatosEncontrados = listOf(contato)
        acaoPendente = { executarLigacao(contato) }
    }

    private fun executarLigacaoWhatsApp(contato: Contato) {
        // Limpa o número
        val numeroLimpo = contato.numero.replace(Regex("[^0-9]"), "")
        val numeroFinal = if (numeroLimpo.startsWith("55")) numeroLimpo else "55$numeroLimpo"
        
        // No Android, para "ligar" direto pelo Zap, precisamos usar uma Intent específica
        // que abre a conversa. Iniciar a CHAMADA de voz automaticamente por código é restrito por segurança,
        // mas podemos deixar a conversa aberta no ponto de clicar no ícone de telefone.
        
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$numeroFinal")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.`package` = "com.whatsapp"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        try {
            startActivity(intent)
            setStatus("WhatsApp aberto para ${contato.nome}")
            falar("Abri a conversa com ${contato.nome}. Agora é só tocar no ícone do telefone lá em cima para ligar.")
        } catch (e: Exception) {
            try {
                val intentFallback = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$numeroFinal"))
                startActivity(intentFallback)
            } catch (e2: Exception) {
                setStatus("WhatsApp não encontrado.")
            }
        }
    }

    private fun mostrarConfirmacaoEscolha(msg: String, opcoes: List<Contato>) {
        // Com múltiplos contatos, exibe o overlay e escuta quem o usuário escolhe
        estado = Estado.AGUARDANDO_CONFIRMACAO
        // Cria ação que escuta a escolha
        acaoPendente = null  // será definida após ouvir o nome
        contatosEncontrados = opcoes

        mostrarOverlayConfirmacao(msg)
        falar(msg) { iniciarEscuta() }
    }

    private fun processarConfirmacao(texto: String) {
        val textoLimpo = texto.replace(Regex("[.\\-,!?]"), "").trim().lowercase(Locale("pt", "BR"))
        
        when {
            "whatsapp" in textoLimpo || "zap" in textoLimpo -> {
                val contato = contatosEncontrados.firstOrNull()
                if (contato != null) {
                    falarDepoisExecutar("Abrindo WhatsApp para ${contato.nome}") {
                        executarLigacaoWhatsApp(contato)
                    }
                }
            }
            "ligar" in textoLimpo || "normal" in textoLimpo || "telefone" in textoLimpo || "sim" in textoLimpo || "pode" in textoLimpo || "isso" in textoLimpo || "quero" in textoLimpo || "confirma" in textoLimpo -> {
                val contato = contatosEncontrados.firstOrNull()
                if (contato != null) {
                    if (isAcaoWhatsAppPendente) {
                        falarDepoisExecutar("Abrindo WhatsApp para ${contato.nome}") { executarLigacaoWhatsApp(contato) }
                    } else {
                        if (acaoPendente != null) {
                            confirmarAcao()
                        } else {
                            // Caso tenha sido uma sugestão de contato
                            falarDepoisExecutar("Ligando para ${contato.nome}") { executarLigacao(contato) }
                        }
                    }
                }
            }
            "não" in textoLimpo || "nao" in textoLimpo || "cancela" in textoLimpo || "pare" in textoLimpo || "desiste" in textoLimpo || "errado" in textoLimpo -> {
                cancelarAcao()
            }
            else -> {
                // Busca o contato mais parecido ignorando acentos e espaços extras
                val encontrado = contatosEncontrados.firstOrNull { contato ->
                    val nomeVoz = normalizarTextoParaComparacao(textoLimpo)
                    val nomeContato = normalizarTextoParaComparacao(contato.nome)
                    nomeVoz.contains(nomeContato) || nomeContato.contains(nomeVoz)
                }

                if (encontrado != null) {
                    if (isAcaoWhatsAppPendente) {
                        falarDepoisExecutar("Abrindo WhatsApp para ${encontrado.nome}") { executarLigacaoWhatsApp(encontrado) }
                    } else {
                        pedirConfirmacaoLigacao(encontrado)
                    }
                } else {
                    falar("Não entendi. Diga sim para confirmar, ou diga o nome de quem você quer.") {
                        Handler(Looper.getMainLooper()).postDelayed({ iniciarEscuta() }, 400)
                    }
                }
            }
        }
    }

    /**
     * Remove acentos e caracteres especiais para comparar nomes de forma robusta.
     * Ex: "Florêncio" vira "florencio"
     */
    private fun normalizarTextoParaComparacao(texto: String): String {
        val semAcentos = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return semAcentos.lowercase(Locale("pt", "BR")).trim()
    }

    private fun confirmarAcao() {
        val acaoParaExecutar = acaoPendente
        esconderConfirmacao()
        acaoPendente = null
        contatosEncontrados = emptyList()
        acaoParaExecutar?.invoke()
    }

    private fun cancelarAcao() {
        esconderConfirmacao()
        acaoPendente = null
        contatosEncontrados = emptyList()
        setStatus("Cancelado. Pronto para ajudar.")
        falar("Tudo bem. Pode me chamar quando precisar.")
    }

    // ─────────────────────────────────────────────────────────────────
    //  OVERLAY DE CONFIRMAÇÃO (SIM / NÃO)
    // ─────────────────────────────────────────────────────────────────

    private fun mostrarOverlayConfirmacao(texto: String) {
        tvConfirmacaoTexto.text = texto
        layoutConfirmacao.visibility = View.VISIBLE
    }

    private fun esconderConfirmacao() {
        layoutConfirmacao.visibility = View.GONE
        estado = Estado.IDLE
    }

    // ─────────────────────────────────────────────────────────────────
    //  AÇÕES FINAIS
    // ─────────────────────────────────────────────────────────────────

    private fun executarLigacao(contato: Contato) {
        if (!temPermissao(Manifest.permission.CALL_PHONE)) {
            pedirPermissoes()
            return
        }
        setStatus("📞 Ligando para ${contato.nome}...")
        falar("Ligando para ${contato.nome}.") {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contato.numero}"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("CALL", "Erro ao ligar: ${e.message}")
                // Fallback para DIAL (abre o discador sem ligar automaticamente)
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contato.numero}"))
                startActivity(dial)
            }
        }
    }

    private fun abrirWhatsApp() {
        val pm = packageManager
        val whatsAppPkg = "com.whatsapp"
        val intentApp = pm.getLaunchIntentForPackage(whatsAppPkg)

        if (intentApp != null) {
            setStatus("Abrindo WhatsApp...")
            startActivity(intentApp)
        } else {
            // WhatsApp não instalado → abre na Play Store
            setStatus("WhatsApp não instalado. Abrindo loja...")
            falar("WhatsApp não está instalado neste aparelho.")
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$whatsAppPkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent) } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$whatsAppPkg")))
            }
        }
    }

    private fun abrirInternet() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com.br"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            setStatus("Nenhum navegador encontrado.")
            falar("Não encontrei um navegador neste aparelho.")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  ERRO
    // ─────────────────────────────────────────────────────────────────

    private fun erroReconhecimento() {
        contatosEncontrados = emptyList() // Limpa para evitar loops de "nome solto"
        setStatus("Não entendi. Tente novamente.")
        falar("Não entendi. Tente de novo.")
    }

    // ─────────────────────────────────────────────────────────────────
    //  STATUS UI
    // ─────────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
        Log.d("STATUS", msg)
    }
}
