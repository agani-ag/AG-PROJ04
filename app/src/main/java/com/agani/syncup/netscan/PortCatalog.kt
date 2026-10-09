package com.agani.syncup.netscan

/** A category for the icon and colour on a service row. */
enum class ServiceGroup { WEB, FILES, DATABASE, REMOTE, PRINT, CAST, MAIL, OTHER }

data class PortInfo(val port: Int, val label: String, val group: ServiceGroup)

/** Plain-word names for the ports SyncUp checks, and the two scan depths. */
object PortCatalog {
    private val NAMED: List<PortInfo> = listOf(
        PortInfo(21, "File transfer (FTP)", ServiceGroup.FILES),
        PortInfo(22, "Remote login (SSH)", ServiceGroup.REMOTE),
        PortInfo(23, "Remote login (Telnet)", ServiceGroup.REMOTE),
        PortInfo(25, "Mail (SMTP)", ServiceGroup.MAIL),
        PortInfo(53, "Name lookups (DNS)", ServiceGroup.OTHER),
        PortInfo(80, "Web page", ServiceGroup.WEB),
        PortInfo(110, "Mail (POP3)", ServiceGroup.MAIL),
        PortInfo(135, "Windows RPC", ServiceGroup.OTHER),
        PortInfo(139, "File share (SMB, older)", ServiceGroup.FILES),
        PortInfo(143, "Mail (IMAP)", ServiceGroup.MAIL),
        PortInfo(161, "Network management (SNMP)", ServiceGroup.OTHER),
        PortInfo(443, "Secure web page", ServiceGroup.WEB),
        PortInfo(445, "File share (SMB)", ServiceGroup.FILES),
        PortInfo(554, "Video stream (RTSP)", ServiceGroup.CAST),
        PortInfo(631, "Printing (IPP)", ServiceGroup.PRINT),
        PortInfo(993, "Mail (IMAP, secure)", ServiceGroup.MAIL),
        PortInfo(995, "Mail (POP3, secure)", ServiceGroup.MAIL),
        PortInfo(1433, "Database (SQL Server)", ServiceGroup.DATABASE),
        PortInfo(1521, "Database (Oracle)", ServiceGroup.DATABASE),
        PortInfo(2049, "File share (NFS)", ServiceGroup.FILES),
        PortInfo(2121, "File transfer (FTP, alt.)", ServiceGroup.FILES),
        PortInfo(3306, "Database (MySQL)", ServiceGroup.DATABASE),
        PortInfo(3389, "Remote desktop (RDP)", ServiceGroup.REMOTE),
        PortInfo(5000, "Web page (alt.)", ServiceGroup.WEB),
        PortInfo(5357, "Device discovery (WSD)", ServiceGroup.OTHER),
        PortInfo(5432, "Database (PostgreSQL)", ServiceGroup.DATABASE),
        PortInfo(5900, "Remote screen (VNC)", ServiceGroup.REMOTE),
        PortInfo(6379, "Database (Redis)", ServiceGroup.DATABASE),
        PortInfo(7000, "Casting (AirPlay)", ServiceGroup.CAST),
        PortInfo(8008, "Casting (Chromecast)", ServiceGroup.CAST),
        PortInfo(8009, "Casting (Chromecast)", ServiceGroup.CAST),
        PortInfo(8080, "Web page (alt.)", ServiceGroup.WEB),
        PortInfo(8443, "Secure web page (alt.)", ServiceGroup.WEB),
        PortInfo(9100, "Printing", ServiceGroup.PRINT),
        PortInfo(27017, "Database (MongoDB)", ServiceGroup.DATABASE),
    )

    private val byPort = NAMED.associateBy { it.port }

    /** Checked by default: the ports most home/office devices actually use. */
    val COMMON: List<Int> = NAMED.map { it.port }.sorted()

    /** Checked by "Extended list": COMMON plus the rest of the well-known range most software picks from. */
    val EXTENDED: List<Int> = (COMMON + listOf(
        20, 37, 43, 49, 67, 68, 69, 70, 79, 88, 111, 113, 119, 123, 137, 138, 177, 179, 194, 199,
        201, 264, 311, 318, 383, 389, 411, 412, 427, 443, 464, 465, 497, 500, 512, 513, 514, 515,
        520, 548, 554, 546, 547, 587, 591, 593, 601, 636, 646, 691, 749, 751, 765, 767, 873, 902,
        903, 989, 990, 992, 1000, 1025, 1026, 1027, 1028, 1029, 1080, 1194, 1214, 1241, 1311, 1337,
        1352, 1414, 1433, 1434, 1512, 1524, 1589, 1701, 1723, 1725, 1741, 1755, 1812, 1813, 1863,
        1900, 1935, 1998, 2000, 2001, 2049, 2082, 2083, 2086, 2087, 2095, 2096, 2181, 2222, 2375,
        2376, 2379, 3000, 3128, 3260, 3268, 3269, 3283, 3306, 3307, 3310, 3333, 3478, 3689, 3690,
        3784, 3785, 4000, 4040, 4111, 4190, 4369, 4444, 4445, 4500, 4567, 4662, 4664, 4848, 4899,
        5001, 5002, 5050, 5051, 5060, 5061, 5190, 5222, 5223, 5228, 5269, 5280, 5281, 5351, 5353,
        5355, 5473, 5555, 5631, 5632, 5666, 5667, 5671, 5672, 5718, 5800, 5938, 5984, 5985, 5986,
        6000, 6001, 6065, 6129, 6346, 6347, 6443, 6500, 6566, 6588, 6600, 6619, 6665, 6666, 6667,
        6668, 6669, 6679, 6697, 6881, 6882, 6969, 6970, 7001, 7070, 7100, 7101, 7199, 7474, 7547,
        7777, 7778, 8000, 8001, 8002, 8081, 8082, 8086, 8087, 8090, 8091, 8096, 8123, 8140, 8181,
        8200, 8222, 8333, 8384, 8388, 8400, 8500, 8554, 8649, 8873, 8880, 8888, 8889, 8899, 8983,
        9000, 9001, 9002, 9009, 9042, 9050, 9051, 9080, 9090, 9091, 9092, 9200, 9300, 9418, 9443,
        9999, 10000, 10001, 10050, 10051, 10250, 11211, 15672, 16992, 16993, 17500, 19132, 20000,
        24800, 25565, 27015, 28017, 32400, 32469, 49152, 49153, 49154, 50000, 50070, 54321,
    )).distinct().sorted()

    /** Plain name for a port, or a generic "Port N" when it isn't one of the named ones (an extended-scan hit). */
    fun nameOf(port: Int): String = byPort[port]?.label ?: "Port $port"
    fun groupOf(port: Int): ServiceGroup = byPort[port]?.group ?: ServiceGroup.OTHER
}
