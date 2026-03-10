package no.nav.delta.faggruppe

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class FaggruppeDTO(
    val id: UUID,
    val navn: String,
    val beskrivelse: String?,
    val undertittel: String?,
    val malgruppe: String?,
    val type: String,
    val tidspunkt: String?,
    val starttid: String?,
    val sluttid: String?,
    @JsonProperty("slack_kanal_navn") val slackKanalNavn: String?,
    @JsonProperty("slack_kanal_url") val slackKanalUrl: String?,
    @JsonProperty("er_aktiv") val erAktiv: Boolean,
    val eiere: List<EierDTO>,
)

data class EierDTO(
    val epost: String,
    val navn: String?,
)

data class FaggruppeCreateDTO(
    val navn: String,
    val type: String,
    val beskrivelse: String? = null,
    val undertittel: String? = null,
    val malgruppe: String? = null,
    val tidspunkt: String? = null,
    val starttid: String? = null,
    val sluttid: String? = null,
    @JsonProperty("slack_kanal_navn") val slackKanalNavn: String? = null,
    @JsonProperty("slack_kanal_url") val slackKanalUrl: String? = null,
)

data class FaggruppeUpdateDTO(
    val navn: String? = null,
    val type: String? = null,
    val beskrivelse: String? = null,
    val undertittel: String? = null,
    val malgruppe: String? = null,
    val tidspunkt: String? = null,
    val starttid: String? = null,
    val sluttid: String? = null,
    @JsonProperty("slack_kanal_navn") val slackKanalNavn: String? = null,
    @JsonProperty("slack_kanal_url") val slackKanalUrl: String? = null,
)

data class AddEierDTO(
    val epost: String,
)
