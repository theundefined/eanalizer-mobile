package com.theundefined.eanalizer.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EneaHtmlTest {
    @Test
    fun authenticatedUrl() {
        assertTrue(EneaHtml.isAuthenticatedUrl("https://ebok.enea.pl/dashboard"))
        assertTrue(EneaHtml.isAuthenticatedUrl("https://ebok.enea.pl/dashboard/many-clients"))
        assertFalse(EneaHtml.isAuthenticatedUrl("https://ebok.enea.pl/logowanie"))
        assertFalse(EneaHtml.isAuthenticatedUrl("https://moja.enea.pl/logowanie?client_id=x"))
        assertFalse(EneaHtml.isAuthenticatedUrl("https://ebok.enea.pl.evil.com/dashboard"))
        assertFalse(EneaHtml.isAuthenticatedUrl("not a url"))
    }

    @Test
    fun allowedNavigation() {
        assertTrue(EneaHtml.isAllowedNavigation("https://moja.enea.pl/logowanie"))
        assertTrue(EneaHtml.isAllowedNavigation("https://sso.moja.enea.pl/auth"))
        assertTrue(EneaHtml.isAllowedNavigation("https://enea.pl/"))
        assertFalse(EneaHtml.isAllowedNavigation("http://moja.enea.pl/logowanie"))
        assertFalse(EneaHtml.isAllowedNavigation("https://notenea.pl/"))
        assertFalse(EneaHtml.isAllowedNavigation("https://google.com/"))
    }

    @Test
    fun loginFormUrl() {
        assertTrue(EneaHtml.isLoginFormUrl("https://moja.enea.pl/logowanie?client_id=x"))
        assertTrue(EneaHtml.isLoginFormUrl("https://moja.enea.pl/logowanie/"))
        assertFalse(EneaHtml.isLoginFormUrl("https://moja.enea.pl/logowanie/kod"))
    }

    @Test
    fun parseCustomers() {
        val html =
            """
            <div><span>12345</span><span>ul. Prosta 1</span>
            <a href="/dashboard/select-current-client/aabbccdd-1122-3344-5566-778899aabbcc">wybierz</a></div>
            <div><span> 67890 </span>
            <a href="/dashboard/select-current-client/00000000-1122-3344-5566-778899aabbcc">wybierz</a></div>
            <a href="/dashboard/select-current-client/ffffffff-1122-3344-5566-778899aabbcc">bez numeru</a>
            """
        assertEquals(
            listOf(
                EneaCustomer("12345", "aabbccdd-1122-3344-5566-778899aabbcc"),
                EneaCustomer("67890", "00000000-1122-3344-5566-778899aabbcc"),
            ),
            EneaHtml.parseCustomers(html),
        )
        assertTrue(EneaHtml.parseCustomers("<html></html>").isEmpty())
    }

    @Test
    fun parseMeterInfo() {
        val info =
            EneaHtml.parseMeterInfo(
                """<div data-point-of-delivery-id="POD123" data-min-date-value="2023-01-01" data-max-date-value="2026"></div>"""
            )
        assertEquals(EneaMeterInfo("POD123", 2023, 2026), info)
        assertNull(EneaHtml.parseMeterInfo("""data-point-of-delivery-id="POD123""""))
    }

    @Test
    fun jsStringEscapes() {
        assertEquals(
            "\"a\\\"b\\\\c\\n\\u003c/script\\u003e\"",
            EneaHtml.jsString("a\"b\\c\n</script>")
        )
        assertTrue(EneaHtml.autofillScript("x@y.pl", "p\"w").contains("\"p\\\"w\""))
    }
}
