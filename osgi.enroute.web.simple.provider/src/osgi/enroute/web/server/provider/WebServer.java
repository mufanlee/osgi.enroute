package osgi.enroute.web.server.provider;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.text.*;
import java.util.*;

import javax.servlet.http.*;

import org.osgi.framework.*;
import org.osgi.namespace.extender.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.log.*;
import org.osgi.util.tracker.*;

import aQute.bnd.annotation.headers.*;
import osgi.enroute.http.capabilities.*;
import osgi.enroute.servlet.api.*;
import osgi.enroute.web.server.cache.*;
import osgi.enroute.webserver.capabilities.*;

@ProvideCapability(
		ns = ExtenderNamespace.EXTENDER_NAMESPACE, 
		name = WebServerConstants.WEB_SERVER_EXTENDER_NAME, 
		version = WebServerConstants.WEB_SERVER_EXTENDER_VERSION)
@RequireHttpImplementation
@Component(
		service = { ConditionalServlet.class }, 
		immediate = true, 
		property = {
				"service.ranking:Integer=1000", 
				"name=" + WebServer.NAME}, 
		name = WebServer.NAME, 
		configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class WebServer implements ConditionalServlet {

	static final String NAME = "osgi.enroute.simple.server";

	static SimpleDateFormat	format							= new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
			Locale.ENGLISH);
	LogService				log;
	Cache					cache;

	WebServerConfig						config;
	BundleTracker< ? >					tracker;

	private ExceptionHandler			exceptionHandler;
	@Activate
	void activate(WebServerConfig config, BundleContext context) throws Exception {
		this.config = config;
		exceptionHandler = new ExceptionHandler(log);

		tracker = new BundleTracker<Bundle>(context, Bundle.ACTIVE | Bundle.STARTING, null) {
			public Bundle addingBundle(Bundle bundle, BundleEvent event) {
				if (bundle.getEntryPaths("static/") != null)
					return bundle;
				return null;
			}
		};
		tracker.open();
	}

	@Override
	public boolean doConditionalService(HttpServletRequest rq, HttpServletResponse rsp) throws Exception {
		try {
			String path = rq.getRequestURI();
			if (path != null && path.startsWith("/"))
				path = path.substring(1);

			FileCache c = getCache(path);
			if(c == null)
				return false;

			rsp.setDateHeader("Last-Modified", c.time);
			rsp.setHeader("Etag", c.etag);
			rsp.setHeader("Content-MD5", c.md5);
			rsp.setHeader("Allow", "GET, HEAD");
			rsp.setHeader("Accept-Ranges", "bytes");

			long diff = 0;
			if (c.expiration != 0)
				diff = c.expiration - System.currentTimeMillis();
			else {
				diff = config.expiration();
				if (diff == 0)
					diff = 120000;
			}

			if (diff > 0) {
				rsp.setHeader("Cache-Control", "max-age=" + diff / 1000);
			}

			if (c.mime != null)
				rsp.setContentType(c.mime);

			Range range = new Range(rq.getHeader("Range"), c.file.length());
			long length = range.length();
			if (length >= Integer.MAX_VALUE)
				throw new IllegalArgumentException("Range to read is too high: " + length);

			rsp.setContentLength((int) range.length());

			if (config.expires() != 0) {
				Date expires = new Date(System.currentTimeMillis() + 60000 * config.expires());
				rsp.setHeader("Expires", format.format(expires));
			}

			String ifModifiedSince = rq.getHeader("If-Modified-Since");
			if (ifModifiedSince != null) {
				long time = 0;
				try {
					synchronized (format) {
						time = format.parse(ifModifiedSince).getTime();
					}
					if (time > c.time) {
						rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
						return true;
					}
				}
				catch (Exception e) {
					// e.printStackTrace();
				}
			}

			String ifNoneMatch = rq.getHeader("If-None-Match");
			if (ifNoneMatch != null) {
				if (ifNoneMatch.indexOf(c.etag) >= 0) {
					rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
					return true;
				}
			}

			if (rq.getMethod().equalsIgnoreCase("GET")) {

				rsp.setContentLengthLong(range.length());
				OutputStream out = rsp.getOutputStream();

				try (FileInputStream file = new FileInputStream(c.file);) {
					FileChannel from = file.getChannel();
					WritableByteChannel to = Channels.newChannel(out);
					range.copy(from, to);
					from.close();
					to.close();
				}

				out.flush();
				out.close();
				rsp.getOutputStream().flush();
				rsp.getOutputStream().close();
			}

			if (c.is404)
				return false;
			else
				rsp.setStatus(HttpServletResponse.SC_OK);

		}
		catch (Exception e ) {
			exceptionHandler.handle(rq, rsp, e);
		}

		return true;
	}

	FileCache getCache(String path) throws Exception {
		FileCache c;
		cache.lock();
		try {
			c = cache.getFromCache(path);
			if (c == null || c.isExpired()) {
				c = findBundle(path);
				if (c == null) {
					c = do404(path);
				} else
					cache.putToCache(path, c);
			}
		} finally {
			cache.unlock();
		}
		return c;
	}

	private FileCache do404(String path) throws Exception {
		log.log(LogService.LOG_INFO, "404 " + path);
		FileCache c = findBundle("404.html");
		if (c == null)
			c = findBundle("default/404.html");
		if (c != null)
			c.is404 = true;

		return c;
	}

	FileCache findBundle(String path) throws Exception {
		Bundle[] bundles = tracker.getBundles();
		if (bundles != null) {
			for (Bundle b : bundles) {
				URL url = cache.urlOf(b, path);
				FileCache c = cache.getFromBundle(b, url, path);
				if(c != null)
					return c;
			}
		}
		return null;
	}

	//-------------- PLUGIN-CACHE --------------
	public File getFile(String path) throws Exception {
		FileCache c = getCache(path);
		if (c == null)
			return null;

		if (!c.isSynched())
			return null;

		return c.file;
	}


	@Deactivate
	void deactivate() {
		tracker.close();
	}

	@Reference
	void setLog(LogService log) {
		this.log = log;
	}

	@Reference
	void setCache(Cache cache) {
		this.cache = cache;
	}
}