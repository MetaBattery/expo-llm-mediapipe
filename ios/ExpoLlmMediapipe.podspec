require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'ExpoLlmMediapipe'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platform       = :ios, '14.0'
  s.swift_version  = '5.4'
  s.source         = { git: 'https://github.com/tirthajyoti-ghosh/expo-llm-mediapipe' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  
  # MediaPipe LLM dependencies - need both
  s.dependency 'MediaPipeTasksGenAI', '>= 0.10.24'
  s.dependency 'MediaPipeTasksGenAIC', '>= 0.10.24'
  
  s.source_files = "*.{swift}"
end