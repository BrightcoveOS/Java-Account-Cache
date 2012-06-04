package com.brightcove.account.cache;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.brightcove.account.cache.exceptions.AccountCacheException;
import com.brightcove.account.cache.exceptions.AccountCacheExceptionCode;
import com.brightcove.commons.account.objects.BrightcoveAccount;
import com.brightcove.commons.catalog.objects.Video;
import com.brightcove.commons.catalog.objects.Videos;
import com.brightcove.commons.catalog.objects.enumerations.ItemStateEnum;
import com.brightcove.commons.catalog.objects.enumerations.SortByTypeEnum;
import com.brightcove.commons.catalog.objects.enumerations.SortOrderTypeEnum;
import com.brightcove.commons.catalog.objects.enumerations.VideoFieldEnum;
import com.brightcove.commons.catalog.objects.enumerations.VideoStateFilterEnum;
import com.brightcove.commons.collection.CollectionUtils;
import com.brightcove.commons.xml.XalanUtils;
import com.brightcove.mediaapi.exceptions.BrightcoveException;
import com.brightcove.mediaapi.wrapper.ReadApi;

public class AccountCache {
	private BrightcoveAccount       account;
	private ReadApi                 readApi;
	private Logger                  logger;
	private Integer                 logLevel;
	private Boolean                 includeDeletedVideos;
	private File                    cacheFile;
	private Boolean                 stripInvalidCharacters;
	
	public static final Integer LOG_SILENT        = 0;
	public static final Integer LOG_INFORMATIONAL = 2;
	public static final Integer LOG_DEBUG         = 5;
	public static final Integer LOG_ALL           = 10;
	
	public static final Set<VideoStateFilterEnum> defaultVideoFilters = VideoStateFilterEnum.CreateFullSet();
	public static final Set<String>               defaultCustomFields = CollectionUtils.CreateEmptyStringSet();
	public static final EnumSet<VideoFieldEnum>   defaultVideoFields  = VideoFieldEnum.CreateFullEnumSet();
	
	private Map<Long,ItemStateEnum>   videosById;
	private Map<String,Long>          videosByReferenceId;
	private Map<Long,Date>            videoLastModifiedDates;
	
	public AccountCache(BrightcoveAccount account){
		init(new ReadApi(), account, Logger.getLogger(this.getClass().getCanonicalName()), LOG_SILENT, new File("./cache.xml"), false);
	}
	
	public AccountCache(BrightcoveAccount account, File cacheFile){
		init(new ReadApi(), account, Logger.getLogger(this.getClass().getCanonicalName()), LOG_SILENT, cacheFile, false);
	}
	
	public AccountCache(BrightcoveAccount account, File cacheFile, ReadApi readApi, Logger logger, Integer logLevel, Boolean includeDeletedVideos){
		init(readApi, account, logger, logLevel, cacheFile, includeDeletedVideos);
	}
	
	public static AccountCache getUpdatedCache(BrightcoveAccount account, File cacheFile, ReadApi readApi, Logger logger, Integer logLevel, Boolean includeDeletedVideos) throws AccountCacheException {
		AccountCache cache = new AccountCache(account, cacheFile, readApi, logger, logLevel, includeDeletedVideos);
		cache.Deserialize();
		cache.UpdateCache();
		cache.Serialize();
		return cache;
	}
	
	private void init(ReadApi readApi, BrightcoveAccount account, Logger logger, Integer logLevel, File cacheFile, Boolean includeDeletedVideos){
		this.readApi              = readApi;
		this.account              = account;
		this.logger               = logger;
		this.logLevel             = logLevel;
		this.cacheFile            = cacheFile;
		this.includeDeletedVideos = includeDeletedVideos;
		
		stripInvalidCharacters = true;
		videosById             = new HashMap<Long, ItemStateEnum>();
		videosByReferenceId    = new HashMap<String, Long>();
		videoLastModifiedDates = new HashMap<Long, Date>();
		
		readApi.setBrightcoveExceptionHandler(new ReadApiExceptionHandler());
	}
	
	public void UpdateCache() throws AccountCacheException {
		UpdateCache(null, null, null);
	}
	
	public void UpdateCache(EnumSet<VideoFieldEnum> videoFields, Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		if((readApi == null) || (account == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_PARAMETERS, "Must set ReadApi and BrightcoveAccount via appropriate constructor before using UpdateCache().");
		}
		
		if(videoFields == null){
			videoFields = defaultVideoFields;
		}
		if(videoFilters == null){
			videoFilters = defaultVideoFilters;
		}
		if(customFields == null){
			customFields = defaultCustomFields;
		}
		
		info("Determining latest modified date in current cache.");
		Date cacheLatestModified = new Date();
		cacheLatestModified.setTime(0l);
		
		if(videoLastModifiedDates.keySet().size() != videosById.keySet().size()){
			info("    Correcting cache file for last modified dates.");
			
			for(Long videoId : videosById.keySet()){
				Video video        = getVideoMetadata(videoId);
				Date  lastModified = video.getLastModifiedDate();
				videoLastModifiedDates.put(videoId, lastModified);
			}
		}
		
		for(Long videoId : videoLastModifiedDates.keySet()){
			Date  lastModified = videoLastModifiedDates.get(videoId);
			if(lastModified != null){
				if(lastModified.after(cacheLatestModified)){
					cacheLatestModified = lastModified;
				}
			}
		}
		info("Latest modified date: '" + cacheLatestModified + "'.");
		
		info("Updating cache from Media API...");
		
		Integer pageNumber = 0;
		Videos  page       = getPage(pageNumber, videoFields, videoFilters, customFields);
		while((page != null) && (page.size() > 0)){
			debug("Reading page '" + pageNumber + "'.");
			
			Boolean cont = true;
			for(Video video : page){
				Long   videoId           = video.getId();
				String refId             = video.getReferenceId();
				Date   videoLastModified = video.getLastModifiedDate();
				
				if(cont){
					debug("    Read video [" + videoId + "," + refId + "] (" + videoLastModified + ").");
				}
				
				addVideo(video);
				
				if((videoLastModified != null) && videoLastModified.before(cacheLatestModified)){
					debug("    Found a video older than the most recent video in the cache.  Assuming we're done (but will finish the page we're on)...");
					cont = false;
				}
			}
			
			if(cont){
				pageNumber++;
				page = getPage(pageNumber, videoFields, videoFilters, customFields);
			}
			else{
				page = null;
			}
		}
		
		Serialize();
	}
	
	public void Serialize() throws AccountCacheException {
		Serialize(true);
	}
	
	public void Serialize(Boolean stripInvalidCharacters) throws AccountCacheException {
		try {
			Document doc  = XalanUtils.createDocument("Videos");
			Element  root = doc.getDocumentElement();
			
			Element byId = doc.createElement("VideosById");
			root.appendChild(byId);
			
			for(Long videoId : videosById.keySet()){
				Element video = doc.createElement("Video");
				byId.appendChild(video);
				
				video.setAttribute("id", ""+videoId);
				video.setAttribute("state", ""+videosById.get(videoId));
			}
			
			Element byRef = doc.createElement("VideosByReferenceId");
			root.appendChild(byRef);
			
			for(String refId : videosByReferenceId.keySet()){
				Element video = doc.createElement("Video");
				byRef.appendChild(video);
				
				video.setAttribute("referenceId", refId);
				video.setAttribute("id", ""+videosByReferenceId.get(refId));
			}
			
			Element byDate = doc.createElement("VideosByDate");
			root.appendChild(byDate);
			
			for(Long videoId : videoLastModifiedDates.keySet()){
				Element video = doc.createElement("Video");
				byDate.appendChild(video);
				
				Long date = 0l;
				if(videoLastModifiedDates.get(videoId) != null){
					date = videoLastModifiedDates.get(videoId).getTime();
				}
				
				video.setAttribute("id", ""+videoId);
				video.setAttribute("lastModifiedDate", ""+date);
			}
			
			if(stripInvalidCharacters){
				XalanUtils.stripNonValidXMLCharacters(doc);
			}
			
			String   xmlString = XalanUtils.prettyPrintWithTrAX(doc);
			FileUtils.writeStringToFile(cacheFile, xmlString, "UTF-8");
		}
		catch (ParserConfigurationException pce) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + pce + " trying to generate XML from account videos.");
		}
		catch (IOException ioe) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + ioe + " trying to generate XML from account videos.");
		}
		catch (TransformerException te) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + te + " trying to generate XML from account videos.");
		}
	}
	
	public void Deserialize() throws AccountCacheException {
		videosById          = new HashMap<Long, ItemStateEnum>();
		videosByReferenceId = new HashMap<String, Long>();
		
		info("Reading cache from disk...");
		
		Document doc = null;
		try{
			info("    Parsing document from disk.");
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			
			factory.setValidating(false);
			
			DocumentBuilder builder = factory.newDocumentBuilder();
			// builder.setErrorHandler(new DefaultErrorHandler(true));
			
			doc = builder.parse(cacheFile);
			
			// String xml = FileUtils.readFileToString(cacheFile, "UTF-8");
		}
		catch(Exception e){
			info("Couldn't read videos from cache file.  Starting from scratch.");
			return;
		}
		
		try {
			String active   = ItemStateEnum.ACTIVE.toString();
			String deleted  = ItemStateEnum.DELETED.toString();
			String inactive = ItemStateEnum.INACTIVE.toString();
			
			info("    Extracting videos by id.");
			List<Node> videos = XalanUtils.getNodesFromXPath(doc, "/Videos/VideosById/Video");
			if(videos != null){
				Integer curIdx    = 0;
				Integer total     = videos.size();
				Long    startTime = (new Date()).getTime();
				info("        count: '" + total + "'.");
				
				for(Node video : videos){
					curIdx++;
					if(curIdx % 100 == 0){
						Long curTime  = (new Date()).getTime();
						Long diffTime = curTime - startTime; // millis
						if(diffTime < 1000l){ diffTime = 1000l; }
						diffTime = diffTime / 1000l; // seconds
						Long rate = curIdx / diffTime;
						minutia("        Processed " + curIdx + " of " + total + " (" + rate + "/sec by id).");
					}
					
					String idString        = ((Element)video).getAttribute("id");
					String itemStateString = ((Element)video).getAttribute("state");
					
					// String idString        = XalanUtils.getStringFromXPath(video, "@id");
					// String itemStateString = XalanUtils.getStringFromXPath(video, "@state");
					
					Long id = Long.parseLong(idString);
					if(active.equals(itemStateString)){
						videosById.put(id, ItemStateEnum.ACTIVE);
					}
					else if(deleted.equals(itemStateString)){
						videosById.put(id, ItemStateEnum.DELETED);
					}
					else if(inactive.equals(itemStateString)){
						videosById.put(id, ItemStateEnum.INACTIVE);
					}
				}
			}
			
			info("    Extracting videos by reference id.");
			videos = XalanUtils.getNodesFromXPath(doc, "/Videos/VideosByReferenceId/Video");
			if(videos != null){
				Integer curIdx    = 0;
				Integer total     = videos.size();
				Long    startTime = (new Date()).getTime();
				info("        count: '" + total + "'.");
				
				for(Node video : videos){
					curIdx++;
					if(curIdx % 100 == 0){
						Long curTime  = (new Date()).getTime();
						Long diffTime = curTime - startTime; // millis
						if(diffTime < 1000l){ diffTime = 1000l; }
						diffTime = diffTime / 1000; // seconds
						Long rate = curIdx / diffTime;
						minutia("        Processed " + curIdx + " of " + total + " (" + rate + "/sec by ref id).");
					}
					
					String refId    = ((Element)video).getAttribute("referenceId");
					String idString = ((Element)video).getAttribute("id");
					
					// String refId    = XalanUtils.getStringFromXPath(video, "@referenceId");
					// String idString = XalanUtils.getStringFromXPath(video, "@id");
					
					Long id = Long.parseLong(idString);
					videosByReferenceId.put(refId, id);
				}
			}
			
			info("    Extracting videos by date.");
			videos = XalanUtils.getNodesFromXPath(doc, "/Videos/VideosByDate/Video");
			if(videos != null){
				Integer curIdx    = 0;
				Integer total     = videos.size();
				Long    startTime = (new Date()).getTime();
				info("        count: '" + total + "'.");
				
				for(Node video : videos){
					curIdx++;
					if(curIdx % 100 == 0){
						Long curTime  = (new Date()).getTime();
						Long diffTime = curTime - startTime; // millis
						if(diffTime < 1000l){ diffTime = 1000l; }
						diffTime = diffTime / 1000; // seconds
						Long rate = curIdx / diffTime;
						minutia("        Processed " + curIdx + " of " + total + " (" + rate + "/sec by date).");
					}
					
					String idString   = ((Element)video).getAttribute("id");
					String dateString = ((Element)video).getAttribute("lastModifiedDate");
					
					// String idString   = XalanUtils.getStringFromXPath(video, "@id");
					// String dateString = XalanUtils.getStringFromXPath(video, "@lastModifiedDate");
					
					Long id   = Long.parseLong(idString);
					Long time = Long.parseLong(dateString);
					Date date = new Date();
					
					date.setTime(time);
					
					videoLastModifiedDates.put(id, date);
				}
			}
		}
		catch (TransformerException te) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + te + " trying to read XML from videos cache.");
		}
		
		info("Cache read.  Total videos: " + videosById.keySet().size() + ".");
	}
	
	public Video getVideoMetadata(Long videoId){
		File metadataFile = idToMetadataFile(videoId);
		
		try {
			Document doc = XalanUtils.parseXml(metadataFile, false);
			Videos videos = new Videos(doc);
			if((videos == null) || (videos.size() < 1)){
				return null;
			}
			return videos.get(0);
		}
		catch (Exception e) {
			return null;
		}
	}
	
	private File idToMetadataFile(Long id){
		if(id == null){
			return null;
		}
		String[] parts = (""+id).split("");
		return new File(cacheFile.getAbsolutePath() + ".metadata/" + CollectionUtils.JoinToString(parts, "/") + "/" + id + ".xml");
	}
	
	private Videos getPage(Integer pageNumber, EnumSet<VideoFieldEnum> videoFields, Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		Long                    fromDate      = 0l;
		Integer                 pageSize      = 100;
		SortByTypeEnum          sortBy        = SortByTypeEnum.MODIFIED_DATE;
		SortOrderTypeEnum       sortOrderType = SortOrderTypeEnum.DESC;
		
		debug("Getting page '" + pageNumber + "'.");
		
		try {
			Videos videos = readApi.FindModifiedVideos(account.getReadToken(), fromDate, videoFilters, pageSize, pageNumber, sortBy, sortOrderType, videoFields, customFields);
			return videos;
		}
		catch (BrightcoveException be) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + be + " trying to generate XML from account videos (via JSON libraries).");
		}
	}
	
	private void addVideo(Video video) throws AccountCacheException {
		if(video == null){
			debug("Asked to add null video to cache.  Ignoring.");
			return;
		}
		
		Long          id                 = video.getId();
		String        refId              = video.getReferenceId();
		ItemStateEnum itemState          = video.getItemState();
		Date          lastModifiedDate   = video.getLastModifiedDate();
		String        lastModifiedString = "null";
		if(lastModifiedDate != null){
			lastModifiedString = ""+lastModifiedDate.getTime();
		}
		debug("Attempting to add video (" + id + "," + refId + "," + lastModifiedString + "," + itemState + ") to cache.");
		
		Video found = null;
		if(id != null){
			found = getVideoByIdUnfiltered(id);
		}
		else{
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Video has no Video Id, can't add to cache.");
		}
		
		if((found == null) && (refId != null)){
			found = getVideoByReferenceIdUnfiltered(refId);
		}
		
		if((itemState != null) && ItemStateEnum.DELETED.equals(itemState)){
			debug("Video is deleted - checking to see if we should remove from cache.");
			
			if(includeDeletedVideos){
				debug("    Cache is including deleted videos - treating as a normal video.");
			}
			else{
				debug("    Cache is discarding deleted videos.  Checking existing cache for removal.");
				
				if(found != null){
					debug("Removing existing video in cache.");
					_removeVideo(found);
				}
				
				return;
			}
		}
		
		_replaceVideo(video, found);
	}
	
	private void _replaceVideo(Video video, Video cached) throws AccountCacheException {
		if(video == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Can't replace with null videos.");
		}
		
		if(cached == null){
			debug("Couldn't find video already in cache, adding.");
			_addVideo(video);
			return;
		}
		
		Date cachedLastModifiedDate = cached.getLastModifiedDate();
		Date lastModifiedDate       = video.getLastModifiedDate();
		if((cachedLastModifiedDate == null) || (lastModifiedDate == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Read API must request Last Modified Date on all videos to function properly.");
		}
		
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z zzzz");
		
		String cachedLastModifiedString = "null";
		if(cachedLastModifiedDate != null){
			cachedLastModifiedString = sdf.format(cachedLastModifiedDate);
		}
		
		String lastModifiedString = "null";
		if(lastModifiedDate != null){
			lastModifiedString = sdf.format(lastModifiedDate);
		}
		
		if(! lastModifiedDate.before(cachedLastModifiedDate)){
			debug("Video is newer than one already in cache (" + lastModifiedString + " vs " + cachedLastModifiedString + ").");
			
			debug("Removing old video (" + videosById.keySet().size() + ").");
			_removeVideo(cached);
			
			debug("Adding new video (" + videosById.keySet().size() + ").");
			_addVideo(video);
			
			debug("Final array size (" + videosById.keySet().size() + ").");
		}
		else{
			debug("Video already in cache is newer (" + lastModifiedString + " vs " + cachedLastModifiedString + ").");
		}
	}
	
	private void _addVideo(Video video) throws AccountCacheException {
		if(video == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Attempted to add null Video, can't add to cache.");
		}
		
		Long   videoId = video.getId();
		String refId   = video.getReferenceId();
		Date   date    = video.getLastModifiedDate();
		
		if(date == null){
			date = new Date();
			date.setTime(0l);
		}
		
		if(videoId == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Video has no Video Id, can't add to cache.");
		}
		
		videosById.put(videoId, video.getItemState());
		if(refId != null){
			videosByReferenceId.put(refId, videoId);
		}
		videoLastModifiedDates.put(videoId, date);
		SerializeVideo(video);
	}
	
	private void _removeVideo(Video video) throws AccountCacheException {
		if(video == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Attempted to remove null Video, can't remove from cache.");
		}
		
		Long   videoId = video.getId();
		String refId   = video.getReferenceId();
		
		if(videoId == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Video has no Video Id, can't remove from cache.");
		}
		
		if(videosById.containsKey(videoId)){
			videosById.remove(videoId);
		}
		
		if(refId != null){
			if(videosByReferenceId.containsKey(refId)){
				videosByReferenceId.remove(refId);
			}
		}
		
		if(videoLastModifiedDates.containsKey(videoId)){
			videoLastModifiedDates.remove(videoId);
		}
		
		File metadataFile = idToMetadataFile(videoId);
		metadataFile.delete();
	}
	
	public void SerializeVideo(Video video) throws AccountCacheException {
		Long videoId = video.getId();
		if(videoId == null){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Video has no Video Id, can't add to cache.");
		}
		
		try {
			File metadataFile = idToMetadataFile(videoId);
			
			Videos videos = new Videos();
			videos.add(video);
			
			File metadataDir = metadataFile.getParentFile();
			if(! metadataDir.exists()){
				info("Creating metadata directory '" + metadataDir.getAbsolutePath() + "'.");
				FileUtils.forceMkdir(metadataDir);
			}
			
			Document doc = videos.toXml();
			
			if(stripInvalidCharacters){
				XalanUtils.stripNonValidXMLCharacters(doc);
			}
			String   xmlString = XalanUtils.prettyPrintWithTrAX(doc);
			FileUtils.writeStringToFile(metadataFile, xmlString, "UTF-8");
		}
		catch (Exception e) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_WRITE_EXCEPTION, "Couldn't serialize video, exception caught: '" + e + "'.");
		}
	}
	
	public Video getVideoById(Long id){
		Video video = getVideoByIdUnfiltered(id);
		if(video == null){
			return null;
		}
		
		ItemStateEnum state = video.getItemState();
		if(state == null){
			return null;
		}
		
		if(! ItemStateEnum.ACTIVE.equals(state)){
			return null;
		}
		
		return video;
	}
	
	public Video getVideoByIdUnfiltered(Long id){
		Video video = getVideoMetadata(id);
		
		return video;
	}
	
	public Video getVideoByReferenceId(String refId){
		info("Looking for reference id '" + refId + "' (with filters).");
		
		Video video = getVideoByReferenceIdUnfiltered(refId);
		if(video == null){
			info("Video '" + refId + "' not found.");
			return null;
		}
		
		ItemStateEnum state = video.getItemState();
		if(state == null){
			info("Video '" + refId + "' found, but couldn't determine item state.");
			return null;
		}
		
		if(! ItemStateEnum.ACTIVE.equals(state)){
			info("Video '" + refId + "' found, but isn't active.");
			return null;
		}
		
		info("Video '" + refId + "' found.");
		return video;
	}
	
	public Video getVideoByReferenceIdUnfiltered(String refId){
		info("Looking for reference id '" + refId + "' without filters.");
		
		if(refId == null){
			return null;
		}
		
		Long videoId = videosByReferenceId.get(refId);
		if(videoId == null){
			return null;
		}
		
		return getVideoByIdUnfiltered(videoId);
	}
	
	private void info(String message){
		if((logger != null) && (logLevel >= LOG_INFORMATIONAL)){
			logger.info(message);
		}
	}
	
	private void debug(String message){
		if((logger != null) && (logLevel >= LOG_DEBUG)){
			logger.info(message);
		}
	}
	
	private void minutia(String message){
		if((logger != null) && (logLevel >= LOG_ALL)){
			logger.info(message);
		}
	}
	
	// ---------------- Getters and Setters ---------------------------
	public BrightcoveAccount getAccount(){
		return account;
	}
	
	public void setAccount(BrightcoveAccount account){
		this.account = account;
	}
	
	public ReadApi getReadApi(){
		return readApi;
	}
	
	public void setReadApi(ReadApi readApi){
		this.readApi = readApi;
	}
	
	public Logger getLogger(){
		return logger;
	}
	
	public void setLogger(Logger logger){
		this.logger = logger;
	}
	
	public Integer getLogLevel(){
		return logLevel;
	}
	
	public void setLogLevel(Integer logLevel){
		this.logLevel = logLevel;
	}
	
	public Boolean getIncludeDeletedVideos(){
		return includeDeletedVideos; 
	}
	
	public void setIncludeDeletedVideos(Boolean includeDeletedVideos){
		this.includeDeletedVideos = includeDeletedVideos;
	}
	
	public File getCacheFile(){
		return cacheFile;
	}
	
	public void setCacheFile(File cacheFile){
		this.cacheFile = cacheFile;
	}
	
	public Boolean getStripInvalidCharacters(){
		return stripInvalidCharacters;
	}
	
	public void setStripInvalidCharacters(Boolean stripInvalidCharacters){
		this.stripInvalidCharacters = stripInvalidCharacters;
	}
	
	public Map<Long,ItemStateEnum> getVideosById(){
		return videosById;
	}
	
	public void setVideosById(Map<Long,ItemStateEnum> videosById){
		this.videosById = videosById;
	}
	
	public Map<String,Long> getVideosByReferenceId(){
		return videosByReferenceId;
	}
	
	public void setVideosByReferenceId(Map<String,Long> videosByReferenceId){
		this.videosByReferenceId = videosByReferenceId;
	}
	
	public Map<Long,Date> getVideoLastModifiedDates(){
		return videoLastModifiedDates;
	}
	
	public void setVideoLastModifiedDates(Map<Long,Date> videoLastModifiedDates){
		this.videoLastModifiedDates = videoLastModifiedDates;
	}
}
