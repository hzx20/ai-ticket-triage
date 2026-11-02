import Base64 from 'crypto-js/enc-base64'
import UTF8 from 'crypto-js/enc-utf8'
import { JSEncrypt } from 'jsencrypt'
import md5 from 'crypto-js/md5'
import CryptoJS from 'crypto-js'

export function encodeByBase64(txt: string) {
  return UTF8.parse(txt).toString(Base64)
}

export function decodeByBase64(txt: string) {
  return Base64.parse(txt).toString(UTF8)
}

export function encryptByMd5(txt: string) {
  return md5(txt).toString()
}

// 部署时替换：与服务器 FIELD_ENCRYPT_PRIVATE_KEY 配对的 RSA-2048 公钥（Base64 的 X.509 SPKI）
// 原值是项目自带的 512 位演示公钥，与生产环境私钥不匹配，会导致登录报「密码解密失败」
const publicKey
  = 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3p8SNIr8mG87bQLsMjrfdhoA1UxH+GEvVhiF'
  + 'lfivUgq7R9l7mDP6CjpL6jcQeh7qaQN4haDB0jHkQ7KI/9Z62VVR66nyko1ASIl/uantqUihWlLxXvoZc'
  + '9PbDUtWJP6MzUgoJVHJKoe0CGQURBUT0ecQvddiF+xU5A7+zhxjFhzouQKHoDYEBkz+k6GvZ0O+YQOfwM'
  + 'IOHLu7QiAnfztcJa9P8icLNInllk9xpqIFXcfMk6cTNuiLWpf43El7mJ/JMxPyeNvM8yUBWu6AR4yVC0A'
  + 'rTlv0azX9ciyY9sKhRRow4ijuAzIqkxfbrDyQpR8Ll8j+YTV44N4YlhGDWq1zXwIDAQAB'

export function encryptByRsa(txt: string) {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey) // 设置公钥
  return encryptor.encrypt(txt) // 对数据进行加密
}

const defaultKeyWork = 'XwKsGlMcdPMEhR1B'

export function encryptByAes(word, keyWord = defaultKeyWork) {
  const key = CryptoJS.enc.Utf8.parse(keyWord)
  const arcs = CryptoJS.enc.Utf8.parse(word)
  const encrypted = CryptoJS.AES.encrypt(arcs, key, {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.Pkcs7,
  })
  return encrypted.toString()
}
