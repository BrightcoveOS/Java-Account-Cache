package com.brightcove.account.cache;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

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
import com.brightcove.commons.xml.XalanUtils;
import com.brightcove.mediaapi.exceptions.BrightcoveException;
import com.brightcove.mediaapi.wrapper.ReadApi;

public class AccountCache {
	private BrightcoveAccount account;
	private ReadApi           readApi;
	private Videos            videos;
	private Logger            logger;
	private Integer           logLevel;
	
	public static final Integer LOG_SILENT        = 0;
	public static final Integer LOG_INFORMATIONAL = 2;
	public static final Integer LOG_DEBUG         = 5;
	public static final Integer LOG_ALL           = 10;
	
	public AccountCache(BrightcoveAccount account){
		init(new ReadApi(), account, Logger.getLogger(this.getClass().getCanonicalName()), LOG_SILENT);
	}
	
	public AccountCache(BrightcoveAccount account, Logger logger){
		init(new ReadApi(logger), account, logger, LOG_INFORMATIONAL);
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account){
		init(readApi, account, Logger.getLogger(this.getClass().getCanonicalName()), LOG_SILENT);
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account, Logger logger){
		init(readApi, account, logger, LOG_INFORMATIONAL);
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account, Logger logger, Integer logLevel){
		init(readApi, account, logger, logLevel);
	}
	
	private void init(ReadApi readApi, BrightcoveAccount account, Logger logger, Integer logLevel){
		this.readApi = readApi;
		this.account = account;
		videos = new Videos();
		this.logger = logger;
		this.logLevel = logLevel;
		
		readApi.setBrightcoveExceptionHandler(new ReadApiExceptionHandler());
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
	
	public void ReadAccount(Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		if((readApi == null) || (account == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_PARAMETERS, "Must set ReadApi and BrightcoveAccount via appropriate constructor before using ReadAccount().");
		}
		
		info("Reading account from Media API...");
		videos = new Videos();
		
		Integer pageNumber = 0;
		Videos  page       = getPage(pageNumber, videoFilters, customFields);
		while((page != null) && (page.size() > 0)){
			debug("Reading page " + pageNumber + " (" + page.getTotalCount() + " total videos).");
			
			for(Video video : page){
				addVideo(video);
			}
			
			pageNumber++;
			page = getPage(pageNumber, videoFilters, customFields);
		}
		
		info("Read complete.  Total videos: " + videos.getTotalCount() + " / " + videos.size() + ".");
	}
	
	private void addVideo(Video video) throws AccountCacheException {
		if(videos == null){
			videos = new Videos();
		}
		
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
		
		if((id == null) && (refId == null)){
			debug("Video identifiers are null.  Adding.");
			videos.add(video);
			return;
		}
		
		Video found = null;
		if(id != null){
			found = getVideoByIdUnfiltered(id);
		}
		if((found == null) && (refId != null)){
			found = getVideoByReferenceIdUnfiltered(refId);
		}
		
		if(found == null){
			debug("Couldn't find video already in cache, adding.");
			videos.add(video);
			return;
		}
		
		
		Long          foundId                 = found.getId();
		String        foundRefId              = found.getReferenceId();
		ItemStateEnum foundItemState          = found.getItemState();
		Date          foundLastModifiedDate   = found.getLastModifiedDate();
		String        foundLastModifiedString = "null";
		if(foundLastModifiedDate != null){
			foundLastModifiedString = ""+foundLastModifiedDate.getTime();
		}
		debug("Found video already in cache (" + foundId + "," + foundRefId + "," + foundLastModifiedString + "," + foundItemState + ").");
		debug("Checking to see if last modified date is newer.");
		
		if((foundLastModifiedDate == null) || (lastModifiedDate == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Read API must request Last Modified Date on all videos to function properly.");
		}
		
		if(! lastModifiedDate.before(foundLastModifiedDate)){
			debug("Video is newer than one already in cache (" + lastModifiedString + " vs " + foundLastModifiedString + ").");
			
			debug("Removing old video (" + videos.size() + ").");
			videos.remove(found);
			
			debug("Adding new video (" + videos.size() + ").");
			videos.add(video);
			
			debug("Final array size (" + videos.size() + ").");
		}
		else{
			debug("Video already in cache is newer (" + lastModifiedString + " vs " + foundLastModifiedString + ").");
		}
	}
	
	// Note that this is intended to keep deleted/inactive videos around in the cache, and may
	// not work as expected if you filter those out with videoFilters.
	public void UpdateCache(Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		if((readApi == null) || (account == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_PARAMETERS, "Must set ReadApi and BrightcoveAccount via appropriate constructor before using UpdateCache().");
		}
		
		if(videos == null){
			videos = new Videos();
		}
		
		info("Determining latest modified date in current cache.");
		Date cacheLatestModified = new Date();
		cacheLatestModified.setTime(0l);
		for(Video video : videos){
			Date lastModified = video.getLastModifiedDate();
			if(lastModified != null){
				if(lastModified.after(cacheLatestModified)){
					cacheLatestModified = lastModified;
				}
			}
		}
		info("Latest modified date: '" + cacheLatestModified + "'.");
		
		info("Updating cache from Media API...");
		
		Integer pageNumber = 0;
		Videos  page       = getPage(pageNumber, videoFilters, customFields);
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
				page = getPage(pageNumber, videoFilters, customFields);
			}
			else{
				page = null;
			}
		}
	}
	
	public void Serialize(File cacheFile) throws AccountCacheException {
		Serialize(cacheFile, true);
	}
	
	public void Serialize(File cacheFile, Boolean stripInvalidCharacters) throws AccountCacheException {
		try {
			Document videoDoc  = videos.toXml();
			if(stripInvalidCharacters){
				XalanUtils.stripNonValidXMLCharacters(videoDoc);
			}
			String   xmlString = XalanUtils.prettyPrintWithTrAX(videoDoc);
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
	
	public void Deserialize(File cacheFile) throws AccountCacheException {
		videos  = new Videos();
		
		info("Reading cache from disk...");
		
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			
			factory.setValidating(false);
			
			DocumentBuilder builder = factory.newDocumentBuilder();
			// builder.setErrorHandler(new DefaultErrorHandler(true));
			
			Document doc = builder.parse(cacheFile);
			
			// String xml = FileUtils.readFileToString(cacheFile, "UTF-8");
			
			videos = new Videos(doc, logger);
		}
		catch (IOException ioe) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + ioe + " trying to read XML from videos cache.");
		}
		catch (ParserConfigurationException pce) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + pce + " trying to read XML from videos cache.");
		}
		catch (SAXException saxe) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + saxe + " trying to read XML from videos cache.");
		}
		
		info("Cache read.  Total videos: " + videos.getTotalCount() + " / " + videos.size() + ".");
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
		for(Video video : videos){
			Long videoId = video.getId();
			if((videoId != null) && videoId.equals(id)){
				return video;
			}
		}
		return null;
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
		
		for(Video video : videos){
			String videoRefId = video.getReferenceId();
			minutia("    Examining video '" + videoRefId + "'.");
			if((videoRefId != null) && videoRefId.equals(refId)){
				info("    Found video '" + videoRefId + "'.");
				return video;
			}
		}
		
		info("Video '" + refId + "' not found.");
		return null;
	}
	
	// ToDo - add other lookup functions
	
	private Videos getPage(Integer pageNumber, Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		Long                    fromDate      = 0l;
		Integer                 pageSize      = 100;
		SortByTypeEnum          sortBy        = SortByTypeEnum.MODIFIED_DATE;
		SortOrderTypeEnum       sortOrderType = SortOrderTypeEnum.DESC;
		EnumSet<VideoFieldEnum> videoFields   = VideoFieldEnum.CreateFullEnumSet();
		
		debug("Getting page '" + pageNumber + "'.");
		
		try {
			Videos videos = readApi.FindModifiedVideos(account.getReadToken(), fromDate, videoFilters, pageSize, pageNumber, sortBy, sortOrderType, videoFields, customFields);
			return videos;
		}
		catch (BrightcoveException be) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + be + " trying to generate XML from account videos (via JSON libraries).");
		}
	}
	
	public Videos getVideos(){
		return videos;
	}
	
	public void setVideos(Videos videos){
		this.videos = videos;
	}
	
	public Integer getLogLevel(){
		return logLevel;
	}
	
	public void setLogLevel(Integer logLevel){
		this.logLevel = logLevel;
	}
}
