package net.osmand.osm.util;

import info.bliki.wiki.filter.HTMLConverter;
import info.bliki.wiki.model.WikiModel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.osm.util.WikiDatabasePreparation.LatLon;

import org.apache.commons.logging.Log;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xwiki.component.embed.EmbeddableComponentManager;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.rendering.converter.Converter;

public class WikiVoyagePreparation {
	private static final Log log = PlatformUtil.getLog(WikiDatabasePreparation.class);
	
	public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, SQLException, ComponentLookupException {
		String lang = "";
		String folder = "";
		if(args.length == 0) {
			lang = "en";
			folder = "/Users/victorshcherb/osmand/wikivoyage/";
		}
		if(args.length > 0) {
			lang = args[0];
		}
		if(args.length > 1){
			folder = args[1];
		}
		final String wikiPg = folder + lang + "wikivoyage-latest-pages-articles.xml.bz2";
		final String sqliteFileName = folder + lang + "wiki.sqlite";
    	
		processWikivoyage(wikiPg, lang, sqliteFileName);
		// testContent(lang, folder);
    }
//
	protected static void processWikivoyage(final String wikiPg, String lang, String sqliteFileName)
			throws ParserConfigurationException, SAXException, FileNotFoundException, IOException, SQLException, ComponentLookupException {
		SAXParser sx = SAXParserFactory.newInstance().newSAXParser();
		InputStream streamFile = new BufferedInputStream(new FileInputStream(wikiPg), 8192 * 4);
		InputStream stream = streamFile;
		if (stream.read() != 'B' || stream.read() != 'Z') {
			throw new RuntimeException(
					"The source stream must start with the characters BZ if it is to be read as a BZip2 stream."); //$NON-NLS-1$
		} 
		CBZip2InputStream zis = new CBZip2InputStream(stream);
		Reader reader = new InputStreamReader(zis,"UTF-8");
		InputSource is = new InputSource(reader);
		is.setEncoding("UTF-8");
		final WikiOsmHandler handler = new WikiOsmHandler(sx, streamFile, lang, new File(sqliteFileName));
		sx.parse(is, handler);
		handler.finish();
	}
	
	public static class WikiOsmHandler extends DefaultHandler {
		long id = 1;
		private final SAXParser saxParser;
		private boolean page = false;
		private boolean revision = false;
		private StringBuilder ctext = null;
		private long cid;

		private StringBuilder title = new StringBuilder();
		private StringBuilder text = new StringBuilder();
		private StringBuilder pageId = new StringBuilder();
		private boolean parseText = false;

		private final InputStream progIS;
		private ConsoleProgressImplementation progress = new ConsoleProgressImplementation();
		private DBDialect dialect = DBDialect.SQLITE;
		private Connection conn;
		private PreparedStatement prep;
		private int batch = 0;
		private final static int BATCH_SIZE = 500;
		final ByteArrayOutputStream bous = new ByteArrayOutputStream(64000);
		private String lang;
		private Converter converter;

		WikiOsmHandler(SAXParser saxParser, InputStream progIS, String lang, File sqliteFile)
				throws IOException, SQLException, ComponentLookupException{
			this.lang = lang;
			this.saxParser = saxParser;
			this.progIS = progIS;
			dialect.removeDatabase(sqliteFile);
			conn = (Connection) dialect.getDatabaseConnection(sqliteFile.getAbsolutePath(), log);
			conn.createStatement().execute("CREATE TABLE wiki(id long, lat double, lon double, title text, zipContent blob)");
			prep = conn.prepareStatement("INSERT INTO wiki VALUES (?, ?, ?, ?, ?)");
			
			progress.startTask("Parse wiki xml", progIS.available());
			EmbeddableComponentManager cm = new EmbeddableComponentManager();
			cm.initialize(WikiDatabasePreparation.class.getClassLoader());
			converter = cm.getInstance(Converter.class);
		}
		
		public void addBatch() throws SQLException {
			prep.addBatch();
			if(batch++ > BATCH_SIZE) {
				prep.executeBatch();
				batch = 0;
			}
		}
		
		public void finish() throws SQLException {
			prep.executeBatch();
			if(!conn.getAutoCommit()) {
				conn.commit();
			}
			prep.close();
			conn.close();
		}

		public int getCount() {
			return (int) (id - 1);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			if (!page) {
				page = name.equals("page");
			} else {
				if (name.equals("title")) {
					title.setLength(0);
					ctext = title;
				} else if (name.equals("text")) {
					if(parseText) {
						text.setLength(0);
						ctext = text;
					}
				} else if (name.equals("revision")) {
					revision  = true;
				} else if (name.equals("id") && !revision) {
					pageId.setLength(0);
					ctext = pageId;
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (page) {
				if (ctext != null) {
					ctext.append(ch, start, length);
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			
			try {
				if (page) {
					if (name.equals("page")) {
						page = false;
						parseText = false;
						progress.remaining(progIS.available());
					} else if (name.equals("title")) {
						ctext = null;
					} else if (name.equals("revision")) {
						revision = false;
					} else if (name.equals("id") && !revision) {
						ctext = null;
						cid = Long.parseLong(pageId.toString());
						parseText = true;// pages.containsKey(cid);
					} else if (name.equals("text")) {
						if (parseText) {
							System.out.println(ctext.toString());
							String text = WikiDatabasePreparation.removeMacroBlocks(ctext.toString());
							final HTMLConverter converter = new HTMLConverter(false);
							WikiModel wikiModel = new WikiModel("http://"+lang+".wikipedia.com/wiki/${image}", "http://"+lang+".wikipedia.com/wiki/${title}");
							String plainStr = wikiModel.render(converter, text);
//							WikiPrinter printer = new DefaultWikiPrinter();
//							System.out.println(text);
//							System.out.println("\n\n");
//							converter.convert(new StringReader(text), Syntax.MEDIAWIKI_1_0, Syntax.XHTML_1_0, printer);
//							String plainStr = printer.toString();
							LatLon ll = new LatLon(0, 0);
							if (id++ % 500 == 0) {
								log.debug("Article accepted " + cid + " " + title.toString() + " " + ll.getLatitude()
										+ " " + ll.getLongitude() + " free: "
										+ (Runtime.getRuntime().freeMemory() / (1024 * 1024)));
//								System.out.println(plainStr);
							}
							try {
								prep.setLong(1, cid);
								prep.setDouble(2, ll.getLatitude());
								prep.setDouble(3, ll.getLongitude());
								prep.setString(4, title.toString());
								bous.reset();
								GZIPOutputStream gzout = new GZIPOutputStream(bous);
								gzout.write(plainStr.getBytes("UTF-8"));
								gzout.close();
								final byte[] byteArray = bous.toByteArray();
								prep.setBytes(5, byteArray);
								addBatch();
							} catch (SQLException e) {
								throw new SAXException(e);
							}
						}
						ctext = null;
					}
				}
			} catch (IOException e) {
				throw new SAXException(e);
			}
		}
		
		
	}
}
