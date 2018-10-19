package work.airz.prifollow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultMetadataType
import com.google.zxing.ResultPoint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.create_user_dialog.view.*
import work.airz.primanager.db.DBConstants
import work.airz.primanager.db.DBFormat
import work.airz.primanager.db.DBUtil
import work.airz.primanager.qr.QRUtil

class MainActivity : AppCompatActivity() {
    private val REQUEST_PERMISSION = 1000
    private lateinit var dbUtil: DBUtil
    private lateinit var qrReaderView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { _ ->
            addUserAlert(this)
        }
        dbUtil = DBUtil(applicationContext)

        while (!checkPermission()) {
            Log.d("permission", "not granted")
            Thread.sleep(2000)
        }
        Log.d("permission", "granted")
        readQR()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.deleteuser -> {
                deleteAccountAlert()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    /**
     * QRコードの読み取り部分の処理
     * 読み取って詳細データまで取得する
     */
    private fun readQR() {
        qrReaderView = decoratedBarcodeView
        qrReaderView.decodeContinuous(object : BarcodeCallback {
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {

            }

            override fun barcodeResult(result: BarcodeResult?) {
                if (result == null || result.barcodeFormat != BarcodeFormat.QR_CODE) return

                val bytes = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as? List<*>
                val data = bytes?.get(0) as? ByteArray ?: return
                val strb = StringBuilder()
                data.forEach { strb.append(String.format("%02X ", it)) }
                Log.i("QR DUMP", strb.toString())

                Log.d("maskIndex", result.result.maskIndex.toString())
                Log.d("QRのサイズ", result.rawBytes.size.toString())

                analyzeQR(data, result)
            }
        })
        qrReaderView.resume()
    }

    fun analyzeQR(data: ByteArray, result: BarcodeResult) {
        qrReaderView.pause()

        val dbUtil = DBUtil(applicationContext)
        val rawString = QRUtil.byteToString(data)

        val qrFormat = QRUtil.QRFormat(QRUtil.QRFormat.getStringToErrorCorrectionLevel(result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL] as String),
                result.result.maskIndex,
                result.sourceData.isInverted, QRUtil.detectVersionM(result.rawBytes.size))
        Log.d("qrformat", qrFormat.toString())

        var ticketType: QRUtil.TicketType = intent.getSerializableExtra(QRUtil.TICKET_TYPE) as? QRUtil.TicketType
                ?: QRUtil.detectQRFormat(data)
        if (dbUtil.getUserList().isEmpty()) {
            accountZeroAlert()
            return
        }

        when (ticketType) {
            QRUtil.TicketType.PRICHAN_FOLLOW -> {
                val followUserID = QRUtil.getFollowUserID(data)
                val followedUsers = dbUtil.getUserList().filter { dbUtil.isFollowed(it, followUserID) }
                val isDuplicate = dbUtil.isDuplicate(DBConstants.FOLLOW_TICKET_TABLE, rawString)
                followAccountAlert(data, qrFormat, QRUtil.TicketType.PRICHAN_FOLLOW)
            }
            else -> {
                othersDataAlert()//謎データであることを告知する
            }
        }

    }

    private fun followAccountAlert(rawData: ByteArray, qrFormat: QRUtil.QRFormat, ticketType: QRUtil.TicketType) {
        val userList = dbUtil.getUserList()
        val userListString = mutableListOf<String>()
        val userFollowList = mutableListOf<Boolean>()
        val targetId = QRUtil.getFollowUserID(rawData)
        userList.forEach { userListString.add(it.userName) }
        userList.forEach { userFollowList.add(dbUtil.isFollowed(it, targetId)) }
        AlertDialog.Builder(this).apply {
            setTitle(resources.getString(R.string.select_follow_account))
            setCancelable(false)
            setMultiChoiceItems(userListString.toTypedArray(), userFollowList.toBooleanArray()) { _, which, isChecked ->
                userFollowList[which] = isChecked
            }
            setPositiveButton(resources.getString(R.string.save)) { dialog, id ->
                userFollowList.withIndex().forEach {
                    if (it.value) {
                        dbUtil.followUser(userList[it.index], DBFormat.UserFollow(targetId, "", "", ""))
                    } else if (it.value == false && dbUtil.isFollowed(userList[it.index], targetId)) {
                        dbUtil.removeFollowUser(userList[it.index], targetId)
                    }
                }
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }
            setNegativeButton(resources.getString(R.string.cancel)) { dialog, which ->
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }

        }.show()
    }

    private fun deleteAccountAlert() {
        val userList = dbUtil.getUserList()
        val userListString = mutableListOf<String>()
        val userListChecked = mutableListOf<Boolean>()
        userList.forEach {
            userListString.add(it.userName)
            userListChecked.add(false)
        }

        AlertDialog.Builder(this).apply {
            setTitle(resources.getString(R.string.select_delete_account))
            setCancelable(false)
            setMultiChoiceItems(userListString.toTypedArray(), userListChecked.toBooleanArray()) { _, which, isChecked ->
                userListChecked[which] = isChecked
            }
            setPositiveButton(resources.getString(R.string.delete)) { dialog, id ->
                userListChecked.withIndex().forEach {
                    if (it.value) {
                        Log.d("ユーザ削除", userList[it.index].userName)
                        dbUtil.removeUser(userList[it.index])
                    }
                }
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }
            setNegativeButton(resources.getString(R.string.cancel)) { dialog, which ->
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }

        }.show()
    }

    /**
     * 謎データが来たときのアラート
     */
    private fun othersDataAlert() {
        AlertDialog.Builder(this).apply {
            setTitle(resources.getString(R.string.unknown_data))
            setCancelable(false)
            setMessage(resources.getString(R.string.please_read_follow_ticket))
            setPositiveButton(resources.getString(R.string.yes)) { dialog, _ ->
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }
        }.show()
    }


    /**
     * アカウント数が0のときのアラート
     */
    private fun accountZeroAlert() {
        AlertDialog.Builder(this).apply {
            setTitle(resources.getString(R.string.user_zero))
            setCancelable(false)
            setMessage(resources.getString(R.string.please_add_user))
            setPositiveButton(resources.getString(R.string.yes)) { dialog, _ ->
                dialog.dismiss()
                decoratedBarcodeView.resume()
            }
        }.show()
    }


    /**
     * ユーザ追加
     * @param context app context
     */
    fun addUserAlert(context: Context) {

        val inflater = LayoutInflater.from(context)
        var dialogRoot = inflater.inflate(R.layout.create_user_dialog, null)

        var editText = dialogRoot.filename
        editText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (dbUtil.isDuplicate(DBConstants.USER_TABLE, dbUtil.getUserHashString(editText.text.toString()))) {
                    dialogRoot.error.visibility = TextView.VISIBLE

                } else {
                    dialogRoot.error.visibility = TextView.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
            }

        })

        var builder = AlertDialog.Builder(context)
        builder.setView(dialogRoot)
        builder.setCancelable(false)
        builder.setNegativeButton(context.resources.getString(R.string.cancel)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setPositiveButton(context.resources.getString(R.string.create)) { _, _ ->
            if (editText.text.toString().isNullOrEmpty()) { //空白チェック
                Toast.makeText(context, context.resources.getText(R.string.please_set_user_name), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            var outputName: String = editText.text.toString()
            // 名前重複チェック
            if (dbUtil.isDuplicate(DBConstants.USER_TABLE, dbUtil.getUserHashString(outputName))) {
                Toast.makeText(context, context.resources.getText(R.string.please_use_defferent_name), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }

            val user = DBFormat.User(
                    dbUtil.getUserHashString(outputName),
                    QRUtil.QRFormat(ErrorCorrectionLevel.M, 1),
                    outputName,
                    "",
                    Bitmap.createScaledBitmap(BitmapFactory.decodeResource(context.resources, R.drawable.baseline_person_add_black_24), 10, 10, false),
                    "",
                    "",
                    "f${dbUtil.getUserHashString(outputName)}"
            )
            dbUtil.addUser(user)
        }
        builder.show()
    }

    private fun checkPermission(): Boolean {
        // 既に許可している
        return if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestStoragePermission()
            false
        }// 拒否していた場合
    }


    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET), REQUEST_PERMISSION)
    }
}
