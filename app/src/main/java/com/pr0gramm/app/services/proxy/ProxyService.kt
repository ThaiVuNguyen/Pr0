package com.pr0gramm.app.services.proxy

import android.net.Uri

/**
 */
interface ProxyService {
    /**
     * Rewrites the URL to use the given proxy.

     * @param url The url to proxy
     */
    fun proxy(url: Uri): Uri
}
