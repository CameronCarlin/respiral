package app.respiral.data.vault

import app.respiral.core.model.VaultEntry

sealed interface VaultHealth {
    data object Loading : VaultHealth
    data object Healthy : VaultHealth
    data class Recovered(val count: Int) : VaultHealth
    data class NeedsAttention(val code: VaultDiagnosticCode, val count: Int) : VaultHealth
}

enum class VaultDiagnosticCode(val displayValue: String) {
    RSP_R02("RSP-R02"),
    RSP_R03("RSP-R03"),
}

data class VaultScan(val entries: List<VaultEntry>, val unreadableCount: Int)
