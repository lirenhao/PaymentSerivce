package controllers

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.apache.commons.codec.binary.Hex
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.{Action, Controller}

import scala.util.{Failure, Random, Success}
import scala.xml.Elem

/**
  * Created by cuitao-pc on 16/7/1.
  */
class WeChatService extends Controller {

  object nonceStr {
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray
    val nonceBytes = new Array[Byte](16)

    def apply(): String = {
      Random.nextBytes(nonceBytes)
      new String(nonceBytes.map(b => chars((b & 0xFF) % chars.length)))
    }
  }

  val key = "4gmfiig9gdqp2lecrzkn4afi4dx8z8v3"
  val mch_id = "1265616601"
  val notify_url = "http://106.39.158.128:8081/rest/weChatCode/payCallback"
  val url = new URL("https://api.mch.weixin.qq.com/pay/unifiedorder")
  val signFieldNames = Array("appid", "mch_id", "nonce_str", "body", "out_trade_no", "total_fee", "spbill_create_ip", "notify_url", "trade_type").sorted

  def unifiedOrder = Action { request =>
    val ip = request.remoteAddress
    var content = request.body.asJson.get.as[JsObject]
    content += ("mch_id", JsString(mch_id))
    content += ("notify_url", JsString(notify_url))
    content += ("nonce_str", JsString(nonceStr()))
    content += ("spbill_create_ip", JsString(ip))
    content += ("sign", JsString(sign(content, key)))
    val xmlStr = content.fields.map(t => "<" + t._1 + ">" + t._2.toString().replace("\"", "") + "</" + t._1 + ">").mkString("")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-type", "application/xml; charset=\"utf-8\"")
    conn.setDoOutput(true)
    conn.setDoInput(true)
    conn.getOutputStream.write(("<xml>" + xmlStr + "</xml>").getBytes(StandardCharsets.UTF_8))
    conn.getOutputStream.flush()
    val raw = new Array[Byte](conn.getHeaderField("Content-Length").toInt)
    conn.getInputStream.read(raw)
    val doc = scala.xml.XML.loadString(new String(raw, StandardCharsets.UTF_8))
    getMessage1(doc) match {
      case Success(xmlDoc) =>
        Ok(Json.toJson(Map("status" -> "success", "prepay_id" -> (xmlDoc \ "prepay_id").head.text, "code_url" -> (xmlDoc \ "code_url").head.text)))
      case Failure(e) =>
        Ok(Json.toJson(Map("status" -> "fail", "message" -> e.getMessage)))
    }
  }

  def sign(content: JsObject, key: String): String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.update((makeContent(content) + "&key=" + key).getBytes(StandardCharsets.UTF_8))
    new String(Hex.encodeHex(digest.digest(), false))
  }

  def makeContent(content: JsObject) = signFieldNames.map(name => {
    val jsVal = (content \ name).get
    name + "=" + (if(jsVal.isInstanceOf[JsString]) jsVal.as[JsString].value else jsVal.toString())
  }).mkString("&")

  def getMessage1(doc: Elem) = (doc \ "return_code").head.text match {
    case "SUCCESS" => getMessage2(doc)
    case "FAIL" => Failure(new Exception((doc \ "return_msg").head.text))
  }

  def getMessage2(doc: Elem) = (doc \ "result_code").head.text match {
    case "SUCCESS" => Success(doc)
    case "FAIL" => Failure(new Exception((doc \ "err_code").head.text + ":" + (doc \ "err_code_des").head.text))
  }
}
