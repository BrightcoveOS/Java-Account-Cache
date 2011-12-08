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
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account){
		this.readApi = readApi;
		this.account = account;
		videos = new Videos();
		logger = null;
	}
	
	public AccountCache(ReadApi readApi, BrightcoveAccount account, Logger logger){
		this.readApi = readApi;
		this.account = account;
		videos = new Videos();
		this.logger = logger;
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
				if(videoExists(video) != null){
					// Video already exists, and should be newer (sorting
					// by last modified date descending)
				}
				else{
					videos.add(video);
				}
			}
			
			pageNumber++;
			page = getPage(pageNumber, videoFilters, customFields);
		}
	}
	
	private Video videoExists(Video video){
		Long    id      = video.getId();
		String  refid   = video.getReferenceId();
		
		if((id != null) && (! "".equals(id))){
			Video exists = getVideoByIdUnfiltered(id);
			if(exists != null){
				return exists;
			}
		}
		if((refid != null) && (! "".equals(refid))){
			Video exists = getVideoByReferenceIdUnfiltered(refid);
			if(exists != null){
				return exists;
			}
		}
		
		return null;
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
		Boolean cont       = true;
		while(cont){
			info("Reading page '" + pageNumber + "'.");
			
			if((page == null) || (page.size() < 1)){
				cont = false;
			}
			else{
				for(Video video : page){
					Long   videoId      = video.getId();
					String refId        = video.getReferenceId();
					Date   lastModified = video.getLastModifiedDate();
					
					if(lastModified == null){
						lastModified = new Date();
						lastModified.setTime(0l);
					}
					
					info("    Read video [" + videoId + "," + refId + "] (" + lastModified + ").");
					
					if(! lastModified.before(latestModified)){
						Video exists = videoExists(video);
						if(exists != null){
							Long   eVideoId = video.getId();
							String eRefId   = video.getReferenceId();
							Date   eLastMod = video.getLastModifiedDate();
							
							info("        Video [" + eVideoId + "," + eRefId + "] already exists (" + eLastMod + ").");
							if(eLastMod == null){
								info("            Replacing existing video with null last modified date/time.");
								videos.remove(exists);
								videos.add(video);
							}
							else if(lastModified.after(eLastMod)){
								info("            Replacing older existing video with newer video.");
								videos.remove(exists);
								videos.add(video);
							}
							else{
								info("            Leaving existing video as it is newer than this one.");
							}
						}
						else{
							info("        New video, adding.");
							videos.add(video);
						}
					}
					else{
						info("    Found a video older than the most recent video in the cache.  Assuming we're done (but will finish the page we're on)...");
						cont = false;
						
						// Replace what's in the cache anyway
						Video exists = videoExists(video);
						if(exists != null){
							videos.remove(exists);
							videos.add(video);
						}
					}
				}
			}
			
			if(cont){
				pageNumber++;
				page = getPage(pageNumber, videoFilters, customFields);
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
		
		Integer currentTry = 0;
		Integer maxTries   = 20;
		while(currentTry < maxTries){
			try {
				Videos videos = readApi.FindModifiedVideos(account.getReadToken(), fromDate, videoFilters, pageSize, pageNumber, sortBy, sortOrderType, videoFields, customFields);
				return videos;
			}
			catch (BrightcoveException be) {
				maxTries++;
				if(currentTry > maxTries){
					throw new AccountCacheException(AccountCacheExceptionCode.ACCOUNT_CACHE_XML_READ_EXCEPTION, "Caught " + be + " trying to generate XML from account videos (via JSON libraries).");
				}
				
				try{ Thread.sleep(60000); } catch(InterruptedException ie){} // Wait 1 minute between retries
			}
		}
		
		return null;
	}
}
