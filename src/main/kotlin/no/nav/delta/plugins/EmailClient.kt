package no.nav.delta.plugins

import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.graph.models.*
import com.microsoft.graph.requests.GraphServiceClient
import java.util.*
import java.util.concurrent.CompletableFuture
import no.nav.delta.Environment

interface EmailClient {
    fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String> = emptyList(),
        ccRecipients: List<String> = emptyList(),
        bccRecipients: List<String> = emptyList()
    )

    companion object {
        fun fromEnvironment(env: Environment): EmailClient {
            val email = env.deltaEmailAddress
            val azureAppClientId = env.azureAppClientId
            val azureAppTenantId = env.azureAppTenantId
            val azureAppClientSecret = env.azureAppClientSecret
            if (email.isEmpty() ||
                azureAppClientId.isEmpty() ||
                azureAppTenantId.isEmpty() ||
                azureAppClientSecret.isEmpty()) {
                return DummyEmailClient()
            }

            return AzureEmailClient(
                applicationEmailAddress = email,
                azureAppClientId = azureAppClientId,
                azureAppTenantId = azureAppTenantId,
                azureAppClientSecret = azureAppClientSecret)
        }
    }
}

class AzureEmailClient(
    private val applicationEmailAddress: String,
    azureAppClientId: String,
    azureAppTenantId: String,
    azureAppClientSecret: String
) : EmailClient {
    private val tokenClient: ConfidentialClientApplication
    private val scopes = setOf("https://graph.microsoft.com/.default")

    private var azureToken: AzureToken? = null
    private val graphClient: GraphServiceClient<okhttp3.Request>

    init {
        val authorityUrl = "https://login.microsoftonline.com/${azureAppTenantId}"
        val clientSecret = ClientCredentialFactory.createFromSecret(azureAppClientSecret)

        this.tokenClient =
            ConfidentialClientApplication.builder(azureAppClientId, clientSecret)
                .authority(authorityUrl)
                .build()
        this.graphClient =
            GraphServiceClient.builder()
                .authenticationProvider {
                    CompletableFuture.completedFuture(this.azureToken?.accessToken)
                }
                .buildClient()
    }

    private fun emailAsRecipient(email: String) =
        Recipient().apply { emailAddress = EmailAddress().apply { address = email } }

    private fun refreshTokenIfNeeded() {
        val currentToken = azureToken
        if (currentToken != null && currentToken.isActive(Date())) {
            return
        }
        refreshToken()
    }

    private fun refreshToken() {
        val authResult =
            tokenClient.acquireToken(ClientCredentialParameters.builder(scopes).build()).get()
        this.azureToken = AzureToken(authResult.accessToken(), authResult.expiresOnDate())
    }

    override fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String>,
        ccRecipients: List<String>,
        bccRecipients: List<String>
    ) {
        if (applicationEmailAddress.isBlank() ||
            (toRecipients.isEmpty() && ccRecipients.isEmpty() && bccRecipients.isEmpty())) {
            return
        }
        refreshTokenIfNeeded()

        val message = Message()
        message.toRecipients = toRecipients.map(this::emailAsRecipient)
        message.ccRecipients = ccRecipients.map(this::emailAsRecipient)
        message.bccRecipients = bccRecipients.map(this::emailAsRecipient)

        message.subject = subject
        message.body =
            ItemBody().apply {
                contentType = BodyType.TEXT
                content = body
            }

        graphClient
            .users(applicationEmailAddress)
            .sendMail(UserSendMailParameterSet.newBuilder().withMessage(message).build())
            .buildRequest()
            .post()
    }
}

private data class AzureToken(val accessToken: String, val expiresOnDate: Date?) {
    fun isActive(currentDate: Date) = expiresOnDate == null || currentDate.before(expiresOnDate)
}

class DummyEmailClient : EmailClient {
    override fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String>,
        ccRecipients: List<String>,
        bccRecipients: List<String>
    ) {
        println(
            "DummyEmailClient: Sending e-mail: subject='$subject' to=$toRecipients, cc=$ccRecipients, bcc=$bccRecipients")
    }
}
