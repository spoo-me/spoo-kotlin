package me.spoo

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.spoo.internal.Transport

/** A linked sign-in provider. */
@Serializable
public data class AuthProvider(
    /** Provider name, e.g. `google` or `github`. */
    val provider: String? = null,
    /** The provider-side account email, when shared. */
    val email: String? = null,
)

/** Profile picture info. */
@Serializable
public data class ProfilePicture(
    /** Picture URL. */
    val url: String? = null,
    /** Where it came from: an OAuth provider name or `upload`. */
    val source: String? = null,
)

/** The signed-in user's profile. */
@Serializable
public data class User(
    /** User id. */
    val id: String,
    /** Email address. */
    val email: String? = null,
    /** Whether the email address is verified. */
    @SerialName("email_verified") val emailVerified: Boolean,
    /** Display name. */
    @SerialName("user_name") val userName: String? = null,
    /** Subscription plan. */
    val plan: String,
    /** Whether the user has set a password. */
    @SerialName("password_set") val passwordSet: Boolean,
    /** Linked sign-in providers. */
    @SerialName("auth_providers") val authProviders: List<AuthProvider> = emptyList(),
    /** Profile picture, when set. */
    val pfp: ProfilePicture? = null,
)

@Serializable
internal class MeWire(val user: User)

/** Identity reads, from [SpooClient.auth]. */
public class Auth internal constructor(
    private val transport: Transport,
) {
    /**
     * Who this client is signed in as. Works with both API keys and Sign
     * in with Spoo sessions.
     */
    public suspend fun me(): User {
        val response = transport.send(HttpMethod.Get, "/auth/me")
        return transport.decode<MeWire>(response).user
    }
}
