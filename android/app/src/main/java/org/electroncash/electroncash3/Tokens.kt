package org.electroncash.electroncash3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.chaquo.python.PyObject
import org.electroncash.electroncash3.databinding.TokensBinding


import android.app.Dialog
import android.widget.EditText

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.fragment.app.DialogFragment

import android.util.Log
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton

import android.widget.TextView
import androidx.fragment.app.Fragment

val guiTokens by lazy { guiMod("tokens") }

// This class is for the dialog to confirm that the user wants to create a new UTXO.
// This is needed if there is no 0-output UTXO which are required for minting.
class ConfirmUTXOCreationDialog : DialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.token_confirm_utxo_creation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tv_confirm).setOnClickListener {
            // User confirms they want to create a new utxo, so call the dialog for signing.
            showSignAndBroadcastPrepUTXODialog()
            dismiss()
        }

        view.findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            // User cancelled the action.
            dismiss()
        }
    }

    private fun showSignAndBroadcastPrepUTXODialog() {
        SignAndBroadcastPrepUTXODialog().show(requireActivity().supportFragmentManager,"SignAndBroadcastPrepUTXODialog")

    }
}

// This clas is for the dialog to specify minting parameters including fungible amount, NFT, etc.
class TokenMintDialog : DialogFragment() {

    private lateinit var etAmount: EditText
    private lateinit var cbNFT: CheckBox
    private lateinit var rgNFTCapabilities: RadioGroup

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->

            val inflater = activity.layoutInflater
            val view = inflater.inflate(R.layout.token_dialog_mint, null)

            etAmount = view.findViewById(R.id.etAmount)
            cbNFT = view.findViewById(R.id.cbNFT)
            rgNFTCapabilities = view.findViewById(R.id.rgNFTCapabilities)
            // Initially disable radio buttons if checkbox is not checked
            rgNFTCapabilities.isEnabled = cbNFT.isChecked

            for (i in 0 until rgNFTCapabilities.childCount) {
                val radioBtn = rgNFTCapabilities.getChildAt(i) as RadioButton
                radioBtn.isEnabled = cbNFT.isChecked
            }

            // Set up listener for the checkbox
            cbNFT.setOnCheckedChangeListener { _, isChecked ->
                // Enable or disable the radio buttons based on the checkbox state
                rgNFTCapabilities.isEnabled = isChecked
                for (i in 0 until rgNFTCapabilities.childCount) {
                    val radioBtn = rgNFTCapabilities.getChildAt(i) as RadioButton
                    radioBtn.isEnabled = isChecked
                }

            if (isChecked && rgNFTCapabilities.checkedRadioButtonId == -1) {
            rgNFTCapabilities.check(R.id.rbNone)
            }
            }
            val builder = AlertDialog.Builder(activity)
            builder.setView(view)
                .setTitle("Mint Token")
                .setPositiveButton("Submit") { dialog, id ->
                    submitTokenMint()
                }
                .setNegativeButton(android.R.string.cancel, null)
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun submitTokenMint() {
        val amount = etAmount.text.toString().toLongOrNull() ?: 0L
        val isNFT = cbNFT.isChecked
        val nftCapability = when (rgNFTCapabilities.checkedRadioButtonId) {
            R.id.rbNone -> "None"
            R.id.rbMutable -> "Mutable"
            R.id.rbMinting -> "Minting"
            else -> "None"
        }

        // Create a bundle with all the mint parameters, pass this to the password dialog.
        val args = Bundle().apply {
            putLong("amount", amount)
            putBoolean("isNFT", isNFT)
            putString("nftCapability", nftCapability)
        }

        // Show the dialog to get the password
        val mintDialog = SignAndBroadcastMintDialog().apply {
            arguments = args
        }

        mintDialog.show(requireActivity().supportFragmentManager,"SignAndBroadcastMintDialog")
    }

}

// This class is for getting the password for the UTXO preparation transaction.
class SignAndBroadcastPrepUTXODialog : DialogFragment() {

    companion object {
        private const val TAG = "SignAndBroadcastPrepUTXODialog"
    }

    private lateinit var passwordInput: EditText
    private lateinit var dialog: AlertDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->
            val inflater = activity.layoutInflater
            val view = inflater.inflate(R.layout.token_prep_utxo_dialog_wallet_password, null)
            passwordInput = view.findViewById(R.id.passwordEditText)

            val builder = AlertDialog.Builder(activity)
            builder.setView(view)
            builder.setTitle("Sign and Broadcast Transaction")
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)

            dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val password = passwordInput.text.toString()
                    signAndBroadcastPrepUTXOTransaction(password)
                }
            }
            dialog
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun signAndBroadcastPrepUTXOTransaction(password: String) {
        try {
            val signedTx = guiTokens.callAttr("create_and_sign_new_coin_tx", wallet, password)
            if (signedTx != null) {
                val broadcastResult = daemonModel.network.callAttr("broadcast_transaction", signedTx)
                if (broadcastResult.asList().get(0).toBoolean()) {
                    Toast.makeText(context, "Transaction broadcasted successfully!", Toast.LENGTH_LONG).show()
                    dialog.dismiss()  // Dismiss the dialog on successful transaction
                } else {
                    val errorMessage = broadcastResult.asList().get(1).toString()
                    Toast.makeText(context, "Failed to broadcast transaction: $errorMessage", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Failed to create transaction.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "${e.message}", Toast.LENGTH_LONG).show()
            // Wrong password. Allow the user to try again or cancel
        }
    }
}

// This class is for the password dialog after minting.
class SignAndBroadcastMintDialog : DialogFragment() {
    private lateinit var passwordInput: EditText
    private var amount: Long? = null
    private var isNFT: Boolean? = null
    private var nftCapability: String? = null

    companion object {
        private const val TAG = "SignAndBroadCastMintDialog"
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->
            val inflater = activity.layoutInflater
            val view = inflater.inflate(R.layout.token_mint_dialog_wallet_password, null)
            passwordInput = view.findViewById(R.id.passwordEditText)

            // Unpack arguments
            amount = arguments?.getLong("amount")
            isNFT = arguments?.getBoolean("isNFT")
            nftCapability = arguments?.getString("nftCapability")

            val builder = AlertDialog.Builder(activity)
            builder.setView(view)
                    .setTitle("Sign and Broadcast Transaction")
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)

            val dialog = builder.create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val password = passwordInput.text.toString()
                    signAndBroadcastMintTransaction(password, dialog)
                }
            }

            dialog
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun signAndBroadcastMintTransaction(password: String, dialog: AlertDialog) {
        try {
            val signedTx = guiTokens.callAttr("create_and_sign_mint_transaction", wallet, amount, isNFT, nftCapability, password)
            if (signedTx != null) {
                val broadcastResult = daemonModel.network.callAttr("broadcast_transaction", signedTx)
                if (broadcastResult.asList().get(0).toBoolean()) {
                    Toast.makeText(context, "Transaction broadcasted successfully!", Toast.LENGTH_LONG).show()
                    dialog.dismiss() // Dismiss dialog on success
                } else {
                    val errorMessage = broadcastResult.asList().get(1).toString()
                    Toast.makeText(context, "Failed to broadcast transaction: $errorMessage", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Failed to create transaction.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // Wrong password.
            Toast.makeText(context, "${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}

// Main entry point for minting.
class TokenMint {

    // First check if we have a minting UTXO. Then either mint the token with UTXO...
    // Or eventually route to create a UTXO.
    fun checkAndMintToken(fragment: Fragment) {
        if (guiTokens.callAttr("wallet_has_minting_utxo", wallet).toBoolean()) {
            mintTokenWithUtxo(fragment)
        } else {
            showNoUTXOConfirmationDialog(fragment)
        }
    }

    private fun showNoUTXOConfirmationDialog(fragment: Fragment) {
        ConfirmUTXOCreationDialog().apply {
            // Setup any required listeners or data here if needed
        }.show(fragment.requireActivity().supportFragmentManager, "ConfirmUTXOCreationDialog")
    }

    private fun mintTokenWithUtxo(fragment: Fragment) {
        TokenMintDialog().show(fragment.requireActivity().supportFragmentManager, "TokenMintDialog")
    }


    private fun showSignAndBroadcastPrepUTXODialog(fragment: Fragment) {
        SignAndBroadcastPrepUTXODialog().show(fragment.requireActivity().supportFragmentManager, "SignAndBroadcastPrepUTXODialog")
    }
}


// This class deals with the main UI display for the screen, displaying each category as a row
class TokensFragment : ListFragment(R.layout.tokens, R.id.rvTokens) {
    private var _binding: TokensBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        _binding = TokensBinding.inflate(LayoutInflater.from(context))
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onListModelCreated(listModel: ListModel) {
        with (listModel) {
            trigger.addSource(daemonUpdate)
            trigger.addSource(settings.getBoolean("cashaddr_format"))
            data.function = { guiTokens.callAttr("get_tokens", wallet)!! }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tokenMint = TokenMint()
        binding.btnAdd.setOnClickListener { tokenMint.checkAndMintToken(this) }
    }

    override fun onCreateAdapter() =
            ListAdapter(this, R.layout.token_list, ::TokenModel, ::TokenDialog)



}

// The model for dealing with the tokens, each row needs the name, amount, nft, and id.
class TokenModel(wallet: PyObject, tokenPy: PyObject) : ListItemModel(wallet) {
    private val tokenMap: Map<String, String> = tokenPy.asMap().mapKeys { it.key.toString() }.mapValues { it.value.toString() }

    val tokenName: String by tokenMap
    val amount: String by tokenMap
    val nft: String by tokenMap
    val tokenId: String by tokenMap

    override val dialogArguments: Bundle by lazy {
        Bundle().apply {
            putString("tokenName", tokenName)
            putString("amount", amount)
            putString("nft", nft)
            putString("tokenId", tokenId)
        }
    }
}


// This class deals with the dialog window that appears when you tap a token row.
class TokenDialog : DetailDialog() {
    private var tokenId: String = "default_token_id"

    override fun onBuildDialog(builder: AlertDialog.Builder) {
        tokenId = arguments?.getString("tokenId", "default_token_id") ?: "default_token_id"
        val displayTokenId = tokenId.take(10) + "..." + tokenId.takeLast(10)
        builder.setTitle("Token Category $displayTokenId")
               .setItems(arrayOf("Category Properties", "Copy Category ID")) { _, which ->
                   when (which) {
                       0 -> showCategoryProperties()
                       1 -> copyCategoryIdToClipboard()
                   }
               }
               .setNegativeButton(android.R.string.cancel, null)
    }

    // When modifying a category, call to open cateogry properties dialog
    private fun showCategoryProperties() {
        val dialog = CategoryPropertiesDialog()
        dialog.arguments = Bundle().apply {
            putString("token_id", tokenId)
        }
        dialog.show(requireActivity().supportFragmentManager, "categoryProperties")
    }

    // Copy to clipboard
    private fun copyCategoryIdToClipboard() {
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Category ID", tokenId)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Token ID copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}


// This class deals with the dialog window for modifying the token properties such as token  name.
class CategoryPropertiesDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->
            val builder = AlertDialog.Builder(activity)
            val inflater = activity.layoutInflater
            val view = inflater.inflate(R.layout.token_category_properties, null)
            val editTextCategoryDetails = view.findViewById<EditText>(R.id.editTextCategoryDetails)

            // Retrieve the token ID passed as an argument
            val tokenId = arguments?.getString("token_id") ?: "default_token_id"
            // Get the existing name from the backend
            val existingTokenData = guiTokens.callAttr("get_token_name", tokenId).toString()
            // Set the existing data in the EditText
            editTextCategoryDetails.setText(existingTokenData)
            val tokenDecimals = if (existingTokenDecimals == "0") "" else existingTokenDecimals
            editTextCategoryDecimals.setText(tokenDecimals)

            builder.setView(view)
            builder.setTitle("Category Properties")
            .setPositiveButton("Save") { dialog, id ->
                val userInput = editTextCategoryDetails.text.toString()
                val inputDecimals = editTextCategoryDecimals.text.toString()
                var decimals = if (inputDecimals == "") {
                    0
                } else {
                    try {
                        inputDecimals.toShort()
                    } catch (e: NumberFormatException) {
                        toast(R.string.Invalid_amount)
                        0
                    }
                }
                if (decimals > 18) {
                    decimals = 18
                // Save the data
                saveTokenData(tokenId, inputName, decimals)
            }
            .setNegativeButton(android.R.string.cancel, null)
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    // Call the Python backend to save the updated token name.
    private fun saveTokenData(tokenId: String, displayName: String) {
        guiTokens.callAttr("save_token_data", tokenId, displayName)
        daemonUpdate.setValue(Unit)  // Needed so the screen refreshes with the updated changes.
    }
}

