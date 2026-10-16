'use strict'

globalThis.lx_setup = (key, id, name, description, version, author, homepage, rawScript) => {
  delete globalThis.lx_setup
  const nativeCallRaw = globalThis.__lx_native_call__
  delete globalThis.__lx_native_call__
  const nativeFuncNames = [
    '__lx_native_call__set_timeout',
    '__lx_native_call__utils_str2b64',
    '__lx_native_call__utils_b642buf',
    '__lx_native_call__utils_bytes2b64',
    '__lx_native_call__utils_str2md5',
    '__lx_native_call__utils_aes_encrypt',
    '__lx_native_call__utils_rsa_encrypt',
    '__lx_native_call__utils_zlib_inflate',
    '__lx_native_call__utils_zlib_deflate',
  ]
  const nativeFuncs = {}
  for (const nativeName of nativeFuncNames) {
    const nativeFunc = globalThis[nativeName]
    delete globalThis[nativeName]
    nativeFuncs[nativeName.replace('__lx_native_call__', '')] = (...args) => nativeFunc(...args)
  }
  const nativeCall = (action, data) => nativeCallRaw(key, action, JSON.stringify(data ?? null))
  const formatConsoleArg = arg => {
    if (typeof arg === 'string') return arg
    if (arg instanceof Error) return arg.stack || arg.message
    try {
      return JSON.stringify(arg)
    } catch (_) {
      return String(arg)
    }
  }
  const installConsoleCompat = () => {
    const sendLog = (...args) => nativeCall('log', args.map(formatConsoleArg).join(' '))
    const noop = () => {}
    globalThis.console = {
      log: sendLog,
      info: sendLog,
      warn: sendLog,
      error: sendLog,
      debug: sendLog,
      trace: sendLog,
      dir: sendLog,
      table: sendLog,
      group: sendLog,
      groupCollapsed: sendLog,
      groupEnd: noop,
      clear: noop,
      time: noop,
      timeEnd: noop,
      timeLog: noop,
      count: sendLog,
      countReset: noop,
      assert(condition, ...args) {
        if (!condition) sendLog(...args)
      },
    }
  }
  installConsoleCompat()
  const EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert',
  }
  const eventNames = Object.values(EVENT_NAMES)
  const events = {
    request: null,
  }
  const requestQueue = new Map()
  const callbacks = new Map()
  let inited = false
  let updateAlertSent = false
  let timeoutId = 0

  const bytesToString = bytes => {
    let result = ''
    let i = 0
    while (i < bytes.length) {
      const byte = bytes[i]
      if (byte < 128) {
        result += String.fromCharCode(byte)
        i += 1
      } else if (byte >= 192 && byte < 224) {
        result += String.fromCharCode(((byte & 31) << 6) | (bytes[i + 1] & 63))
        i += 2
      } else {
        result += String.fromCharCode(((byte & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63))
        i += 3
      }
    }
    return result
  }

  const stringToBytes = input => {
    const bytes = []
    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i)
      if (code < 128) {
        bytes.push(code)
      } else if (code < 2048) {
        bytes.push((code >> 6) | 192)
        bytes.push((code & 63) | 128)
      } else {
        bytes.push((code >> 12) | 224)
        bytes.push(((code >> 6) & 63) | 128)
        bytes.push((code & 63) | 128)
      }
    }
    return bytes
  }

  const sendNativeHttp = (url, options, callback) => {
    const requestKey = `${Date.now()}-${Math.random()}`
    const requestInfo = {
      aborted: false,
      abort() {
        requestInfo.aborted = true
        nativeCall('cancelRequest', requestKey)
      },
    }
    requestQueue.set(requestKey, { callback, requestInfo })
    nativeCall('request', { requestKey, url, options })
    return requestInfo
  }

  const handleNativeHttpResponse = data => {
    const target = requestQueue.get(data.requestKey)
    if (!target) return
    requestQueue.delete(data.requestKey)
    target.requestInfo.aborted = true
    if (data.error == null) target.callback(null, normalizeHttpResponse(data.response))
    else target.callback(new Error(data.error), null)
  }

  const base64ToBytes = input => {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    const clean = String(input || '')
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .replace(/[^A-Za-z0-9+/]/g, '')
    const output = new Uint8Array(Math.floor(clean.length * 6 / 8))
    let bits = 0
    let buffer = 0
    let outputIndex = 0
    for (let index = 0; index < clean.length; index++) {
      const value = alphabet.indexOf(clean[index])
      if (value < 0) continue
      buffer = (buffer << 6) | value
      bits += 6
      if (bits < 8) continue
      bits -= 8
      output[outputIndex++] = (buffer >>> bits) & 0xff
      buffer = bits === 0 ? 0 : buffer & ((1 << bits) - 1)
    }
    return outputIndex === output.length ? output : output.slice(0, outputIndex)
  }

  const normalizeHttpResponse = response => {
    if (!response || typeof response !== 'object') return response
    if (typeof response.rawBase64 === 'string') {
      const raw = makeBufferLike(base64ToBytes(response.rawBase64))
      response.raw = raw
      response.bytes = raw
      delete response.rawBase64
    }
    if (Array.isArray(response.raw)) response.raw = utils.buffer.from(response.raw)
    if (Array.isArray(response.bytes)) response.bytes = utils.buffer.from(response.bytes)
    return response
  }

  const isHttpUrl = value => typeof value === 'string' && /^https?:\/\//i.test(value.trim())
  const errorToMessage = error => {
    if (!error) return String(error)
    const message = error.message ? String(error.message) : ''
    const stack = error.stack ? String(error.stack) : ''
    if (message && stack && !stack.includes(message)) return `${message}\n${stack}`
    if (stack) return stack
    if (message) return message
    return String(error)
  }

  const wrapMusicUrl = (data, url) => ({
    source: data.source,
    action: data.action,
    data: {
      type: data.info && data.info.type,
      url: url.trim(),
    },
  })

  const normalizeMusicUrlResult = (data, response) => {
    if (isHttpUrl(response)) {
      return wrapMusicUrl(data, response)
    }
    if (response && typeof response === 'object') {
      if (response.data && typeof response.data === 'object' && isHttpUrl(response.data.url)) {
        return response
      }
      if (isHttpUrl(response.data)) {
        return wrapMusicUrl(data, response.data)
      }
      if (isHttpUrl(response.url)) {
        return wrapMusicUrl(data, response.url)
      }
    }
    throw new Error('musicUrl response is not a playable url')
  }

  const handleAppRequest = ({ requestKey, data }) => {
    if (!events.request) {
      nativeCall('response', { requestKey, status: false, errorMessage: 'Request event is not defined' })
      return
    }
    try {
      const task = events.request.call(globalThis.lx, {
        source: data.source,
        action: data.action,
        info: data.info,
      })
      if (!task || typeof task.then !== 'function') {
        nativeCall('response', { requestKey, status: false, errorMessage: 'Request handler must return Promise' })
        return
      }
      Promise.resolve(task).then(response => {
        let result = response
        if (data.action === 'musicUrl') {
          result = normalizeMusicUrlResult(data, response)
        }
        nativeCall('response', { requestKey, status: true, result })
      }).catch(error => {
        nativeCall('response', { requestKey, status: false, errorMessage: errorToMessage(error) })
      })
    } catch (error) {
      nativeCall('response', { requestKey, status: false, errorMessage: errorToMessage(error) })
    }
  }

  const handleSetTimeout = id => {
    const target = callbacks.get(id)
    if (!target) return
    callbacks.delete(id)
    target.callback(...target.params)
  }

  globalThis.__lx_native__ = (nativeKey, action, data) => {
    if (nativeKey !== key) return
    const payload = typeof data === 'string' ? JSON.parse(data) : data
    if (action === 'response') {
      handleNativeHttpResponse(payload)
    } else if (action === 'request') {
      handleAppRequest(payload)
    } else if (action === '__set_timeout__') {
      handleSetTimeout(payload)
    } else if (action === '__run_error__') {
      nativeCall('init', { status: false, errorMessage: 'Script run failed', info: null })
    }
  }

  const setTimeoutCompat = (callback, timeout = 0, ...params) => {
    if (typeof callback !== 'function') throw new Error('callback required a function')
    const currentId = timeoutId++
    callbacks.set(currentId, { callback, params })
    nativeFuncs.set_timeout(currentId, parseInt(timeout, 10) || 0)
    return currentId
  }

  const clearTimeoutCompat = currentId => {
    callbacks.delete(currentId)
  }

  const toByteArray = input => {
    if (input instanceof ArrayBuffer) return Array.from(new Uint8Array(input))
    if (ArrayBuffer.isView(input)) return Array.from(new Uint8Array(input.buffer, input.byteOffset, input.byteLength))
    if (Array.isArray(input)) return input.map(value => Number(value) & 0xff)
    throw new Error('Input is not a valid buffer')
  }

  const bytesToBase64 = input => nativeFuncs.utils_bytes2b64(JSON.stringify(toByteArray(input)))

  const binaryStringToBytes = input => {
    const bytes = []
    for (let i = 0; i < input.length; i++) bytes.push(input.charCodeAt(i) & 0xff)
    return bytes
  }

  const bytesToBinaryString = input => {
    const bytes = toByteArray(input)
    let result = ''
    for (let i = 0; i < bytes.length; i++) result += String.fromCharCode(bytes[i] & 0xff)
    return result
  }

  const dataToB64 = data => {
    if (typeof data === 'string') return nativeFuncs.utils_str2b64(data)
    if (Array.isArray(data) || ArrayBuffer.isView(data) || data instanceof ArrayBuffer) return bytesToBase64(data)
    throw new Error('data type error: ' + typeof data)
  }

  const makeBufferLike = bytes => {
    const buffer = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
    Object.defineProperty(buffer, 'toString', {
      value: function toString(format) {
        return utils.buffer.bufToString(this, format)
      },
      writable: true,
      configurable: true,
      enumerable: false,
    })
    return buffer
  }

  const utils = {
    crypto: {
      aesEncrypt(buffer, mode, aesKey, iv) {
        switch (mode) {
          case 'aes-128-cbc':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(aesKey), dataToB64(iv), 'aes-128-cbc'), 'base64')
          case 'aes-128-ecb':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(aesKey), '', 'aes-128-ecb'), 'base64')
          default:
            throw new Error('Unsupported AES mode: ' + mode)
        }
      },
      rsaEncrypt(buffer, publicKey) {
        if (typeof publicKey !== 'string') throw new Error('Invalid RSA key')
        const keyBody = publicKey
          .replace('-----BEGIN PUBLIC KEY-----', '')
          .replace('-----END PUBLIC KEY-----', '')
          .replace(/\s+/g, '')
        return utils.buffer.from(nativeFuncs.utils_rsa_encrypt(dataToB64(buffer), keyBody, 'RSA/ECB/NoPadding'), 'base64')
      },
      randomBytes(size) {
        const bytes = new Uint8Array(size)
        for (let i = 0; i < size; i++) bytes[i] = Math.floor(Math.random() * 256)
        return bytes
      },
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeFuncs.utils_str2md5(encodeURIComponent(str))
      },
    },
    buffer: {
      from(input, encoding) {
        if (typeof input === 'string') {
          switch (encoding) {
            case 'base64':
              return makeBufferLike(base64ToBytes(input))
            case 'hex':
              return makeBufferLike((input.match(/.{1,2}/g) || []).map(byte => parseInt(byte, 16)))
            case 'binary':
              return makeBufferLike(binaryStringToBytes(input))
            default:
              return makeBufferLike(stringToBytes(input))
          }
        }
        if (input instanceof ArrayBuffer) return makeBufferLike(input)
        if (Array.isArray(input) || ArrayBuffer.isView(input)) return makeBufferLike(input)
        throw new Error('Unsupported input type')
      },
      bufToString(buffer, format) {
        if (!Array.isArray(buffer) && !ArrayBuffer.isView(buffer) && !(buffer instanceof ArrayBuffer)) throw new Error('Input is not a valid buffer')
        const bytes = toByteArray(buffer)
        switch (format) {
          case 'binary':
            return bytesToBinaryString(bytes)
          case 'hex':
            return bytes.reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '')
          case 'base64':
            return bytesToBase64(bytes)
          case 'utf8':
          case 'utf-8':
          default:
            return bytesToString(bytes)
        }
      },
    },
    zlib: {
      inflate(buf) {
        return Promise.resolve(utils.buffer.from(nativeFuncs.utils_zlib_inflate(dataToB64(buf)), 'base64'))
      },
      deflate(data) {
        return Promise.resolve(utils.buffer.from(nativeFuncs.utils_zlib_deflate(dataToB64(data)), 'base64'))
      },
    },
  }

  globalThis.lx = Object.freeze({
    version: '2.0.0',
    env: 'desktop',
    currentScriptInfo: Object.freeze({ id, name, description, version, author, homepage, rawScript }),
    EVENT_NAMES,
    request(url, options = {}, callback) {
      if (typeof callback !== 'function') throw new Error('callback required')
      const method = String(options.method || 'get').toUpperCase()
      const headers = options.headers || {}
      const body = options.body || options.data || null
      const form = options.form || null
      const formData = options.formData || null
      const binary = options.binary === true
      const timeout = Number(options.timeout || 60000)
      const request = sendNativeHttp(url, { method, headers, body, form, formData, binary, timeout }, (error, response) => {
        if (error) callback(error, null, null)
        else callback(null, response, response.body)
      })
      return () => {
        if (!request.aborted) request.abort()
      }
    },
    send(eventName, data) {
      return new Promise((resolve, reject) => {
        if (!eventNames.includes(eventName)) return reject(new Error('Unknown event name'))
        switch (eventName) {
          case EVENT_NAMES.inited:
            if (inited) return reject(new Error('Script is inited'))
            inited = true
            nativeCall('init', { status: true, info: data || {} })
            resolve()
            break
          case EVENT_NAMES.updateAlert:
            if (updateAlertSent) return resolve()
            updateAlertSent = true
            nativeCall('showUpdateAlert', data || {})
            resolve()
            break
          default:
            reject(new Error('Unknown event name'))
        }
      })
    },
    on(eventName, handler) {
      if (eventName !== EVENT_NAMES.request) return Promise.reject(new Error('Unknown event name'))
      if (typeof handler !== 'function') return Promise.reject(new Error('handler must be function'))
      events.request = handler
      return Promise.resolve()
    },
    utils: Object.freeze(utils),
  })

  globalThis.setTimeout = setTimeoutCompat
  globalThis.clearTimeout = clearTimeoutCompat

  const freezeObject = obj => {
    if (typeof obj !== 'object' || obj == null) return
    Object.freeze(obj)
    for (const subObj of Object.values(obj)) freezeObject(subObj)
  }
  freezeObject(globalThis.lx)

  console.log('Preload finished.')
}
