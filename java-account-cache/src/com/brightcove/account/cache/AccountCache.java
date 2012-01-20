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
	BrightcoveAccount account;
	ReadApi           readApi;
	Videos            videos;
	Logger            logger;
	
	public AccountCache(BrightcoveAccount account){
		init(new ReadApi(), account, null);
	}
	
	public AccountCache(BrightcoveAccount account, Logger logger){
		init(new ReadApi(logger), account, logger);
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account){
		init(readApi, account, null);
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account, Logger logger){
		init(readApi, account, logger);
	}
	
	private void init(ReadApi readApi, BrightcoveAccount account, Logger logger){
		this.readApi = readApi;
		this.account = account;
		videos = new Videos();
		this.logger = logger;
		
		readApi.setBrightcoveExceptionHandler(new ReadApiExceptionHandler());
	}
	
	private void info(String message){
		if(logger != null){
			logger.info(message);
		}
	}
	
	public void ReadAccount(Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		if((readApi == null) || (account == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_PARAMETERS, "Must set ReadApi and BrightcoveAccount via appropriate constructor before using ReadAccount().");
		}
		
		videos = new Videos();
		
		Integer pageNumber = 0;
		Videos  page       = getPage(pageNumber, videoFilters, customFields);
		while((page != null) && (page.size() > 0)){
			info("Reading page " + pageNumber + " (" + page.getTotalCount() + " total videos).");
			
			for(Video video : page){
				addVideo(video);
			}
			
			pageNumber++;
			page = getPage(pageNumber, videoFilters, customFields);
		}
	}
	
	private void addVideo(Video video) throws AccountCacheException {
		if(videos == null){
			videos = new Videos();
		}
		
		if(video == null){
			info("Asked to add null video to cache.  Ignoring.");
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
		info("Attempting to add video (" + id + "," + refId + "," + lastModifiedString + "," + itemState + ") to cache.");
		
		if((id == null) && (refId == null)){
			info("Video identifiers are null.  Adding.");
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
			info("Couldn't find video already in cache, adding.");
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
		info("Found video already in cache (" + foundId + "," + foundRefId + "," + foundLastModifiedString + "," + foundItemState + ").");
		info("Checking to see if last modified date is newer.");
		
		if((foundLastModifiedDate == null) || (lastModifiedDate == null)){
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_MISSING_FIELDS, "Read API must request Last Modified Date on all videos to function properly.");
		}
		
		if(! lastModifiedDate.before(foundLastModifiedDate)){
			info("Video is newer than one already in cache (" + lastModifiedString + " vs " + foundLastModifiedString + ").");
			
			info("Removing old video (" + videos.size() + ").");
			videos.remove(found);
			
			info("Adding new video (" + videos.size() + ").");
			videos.add(video);
			
			info("Final array size (" + videos.size() + ").");
		}
		else{
			info("Video already in cache is newer (" + lastModifiedString + " vs " + foundLastModifiedString + ").");
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
		Date latestModified = new Date();
		latestModified.setTime(0l);
		for(Video video : videos){
			Date lastModified = video.getLastModifiedDate();
			if(lastModified != null){
				if(lastModified.after(latestModified)){
					latestModified = lastModified;
				}
			}
		}
		info("Latest modified date: '" + latestModified + "'.");
		
		Integer pageNumber = 0;
		Videos  page       = getPage(pageNumber, videoFilters, customFields);
		while((page != null) && (page.size() > 0)){
			info("Reading page '" + pageNumber + "'.");
			
			Boolean cont = true;
			for(Video video : page){
				Long   videoId      = video.getId();
				String refId        = video.getReferenceId();
				Date   lastModified = video.getLastModifiedDate();
				
				if(cont){
					info("    Read video [" + videoId + "," + refId + "] (" + lastModified + ").");
				}
				
				addVideo(video);
				
				if((lastModified != null) && lastModified.before(latestModified)){
					info("    Found a video older than the most recent video in the cache.  Assuming we're done (but will finish the page we're on)...");
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
		try {
			Document videoDoc  = videos.toXml();
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
		Video video = getVideoByReferenceIdUnfiltered(refId);
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
	
	public Video getVideoByReferenceIdUnfiltered(String refId){
		for(Video video : videos){
			String videoRefId = video.getReferenceId();
			if((videoRefId != null) && videoRefId.equals(refId)){
				return video;
			}
		}
		return null;
	}
	
	// ToDo - add other lookup functions
	
	private Videos getPage(Integer pageNumber, Set<VideoStateFilterEnum> videoFilters, Set<String> customFields) throws AccountCacheException {
		Long                    fromDate      = 0l;
		Integer                 pageSize      = 100;
		SortByTypeEnum          sortBy        = SortByTypeEnum.MODIFIED_DATE;
		SortOrderTypeEnum       sortOrderType = SortOrderTypeEnum.DESC;
		EnumSet<VideoFieldEnum> videoFields   = VideoFieldEnum.CreateFullEnumSet();
		
		try {
			Videos videos = readApi.FindModifiedVideos(account.getReadToken(), fromDate, videoFilters, pageSize, pageNumber, sortBy, sortOrderType, videoFields, customFields);
			return videos;
		}
		catch (BrightcoveException be) {
			throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + be + " trying to generate XML from account videos (via JSON libraries).");
		}
	}
}
