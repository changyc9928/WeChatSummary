#!/bin/bash

# Configuration
API_KEY="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"
MODEL="meta/llama-3.2-90b-vision-instruct"
IMAGE_PATH="/Users/yincheng.chang/uploads/f6d171cc-a638-49e6-9bdd-de7960ebf0dd/2a8a2693-0cb3-42a7-ac24-be3e6a071664/images/20260728/1785235169_d44ffe96e58be1b48f3b05b70e2b7b7a.jpg"

# Convert image to base64 data URI
BASE64_IMAGE=$(base64 < "$IMAGE_PATH" | tr -d '\n')
DATA_URI="data:image/jpeg;base64,$BASE64_IMAGE"

# Construct and execute the curl request
curl -s -X POST "https://integrate.api.nvidia.com/v1/chat/completions" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d @- <<EOF
{
  "model": "$MODEL",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Describe what you see in this image."
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "$DATA_URI"
          }
        }
      ]
    }
  ],
  "max_tokens": 512,
  "temperature": 0.7
}
EOF
